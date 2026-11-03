#requires -Version 7.0
[CmdletBinding()]
param(
    [uri]$BackendUrl = 'http://127.0.0.1:8080',
    [uri]$AgentUrl = 'http://127.0.0.1:17374',
    [guid]$ResumeId = [guid]::Empty,
    [string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'hh-clicker-diagnostics')
)
$ErrorActionPreference = 'Stop'
foreach ($address in @($BackendUrl, $AgentUrl)) {
    if (-not $address.IsLoopback -or $address.Scheme -notin @('http', 'https') -or $address.AbsolutePath -ne '/') {
        throw 'Этот контрольный прогон рассчитан только на локальные backend и Generation Agent.'
    }
}
$backendBase = $BackendUrl.AbsoluteUri.TrimEnd('/')
$agentBase = $AgentUrl.AbsoluteUri.TrimEnd('/')
$health = Invoke-RestMethod "$agentBase/health" -TimeoutSec 20
if ($null -eq $health.PSObject.Properties['runningRelevance']) {
    throw 'Generation Agent ещё не обновлён: отсутствует метрика runningRelevance. Сначала установите новый nightly.'
}
if ($null -eq $health.PSObject.Properties['resources']) {
    throw 'Установите nightly с адаптивным бюджетом памяти: в health отсутствует resources.'
}
if ($ResumeId -eq [guid]::Empty) {
    $overview = Invoke-RestMethod "$backendBase/api/v1/overview" -TimeoutSec 20
    if (@($overview.resumes).Count -ne 1) { throw 'Для нескольких резюме укажите -ResumeId <UUID>.' }
    $ResumeId = [guid]$overview.resumes[0].id
}
# Read-only preflight: an older backend does not expose this route.
$null = Invoke-RestMethod "$backendBase/api/v1/vacancies/relevance/parallel/active?resumeId=$ResumeId" -TimeoutSec 20
$cards = Invoke-RestMethod "$backendBase/api/v1/vacancies?resumeId=$ResumeId" -TimeoutSec 30
if ($cards.Count -eq 0) { throw 'Нет сохранённых вакансий для контрольного прогона.' }
$ids = @($cards | ForEach-Object { [string]$_.id })
$selected = [System.Collections.Generic.HashSet[string]]::new([string[]]$ids)
function Get-RatingHash($items) {
    $values = @($items | Where-Object { $selected.Contains([string]$_.id) } |
        Sort-Object id | Select-Object id, relevance)
    $json = ConvertTo-Json -InputObject $values -Depth 15 -Compress
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json)))
}
$beforeHash = Get-RatingHash $cards
$body = @{ vacancyIds = $ids; benchmark = $true; evaluatedBefore = 0; totalCount = $cards.Count } |
    ConvertTo-Json -Depth 4 -Compress
$samples = [System.Collections.Generic.List[object]]::new()
$watch = [Diagnostics.Stopwatch]::StartNew()
$run = $null
$finished = $false
$peak = 0
$healthWarning = $false
try {
    $run = Invoke-RestMethod "$backendBase/api/v1/vacancies/relevance/parallel" -Method Post `
        -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 60
    if (-not $run.benchmark) { throw 'Backend не подтвердил контрольный режим.' }
    Write-Host "Контрольный запуск $($run.id): $($run.requestedCount) вакансий, $($run.batchCount) партий. Оценки не сохраняются."
    while ($run.status -in @('queued', 'running')) {
        if ($watch.Elapsed.TotalMinutes -gt 30) { throw 'Превышено время ожидания контрольного прогона.' }
        try {
            $health = Invoke-RestMethod "$agentBase/health" -TimeoutSec 5
            $peak = [Math]::Max($peak, [int]$health.runningRelevance)
            $samples.Add(@{ seconds = [Math]::Round($watch.Elapsed.TotalSeconds, 1);
                running = $health.runningRelevance; queued = $health.queuedRelevance;
                budgetBytes = $health.resources.budgetBytes; availableBytes = $health.resources.availableBytes;
                perJobBytes = $health.resources.perJobBytes; capacity = $health.resources.capacity;
                maxConcurrentRelevance = $health.resources.maxConcurrentRelevance;
                waitingReason = $health.resources.waitingReason })
        } catch {
            if (-not $healthWarning) { Write-Warning 'Метрики агента временно недоступны; пик может быть занижен.'; $healthWarning = $true }
        }
        Write-Host ("{0:N1} с: партии {1}/{2}, оценено {3}/{4}, пик одновременно {5}" -f `
            $watch.Elapsed.TotalSeconds, $run.completedBatches, $run.batchCount, $run.evaluatedCount, $run.requestedCount, $peak)
        Start-Sleep -Seconds 1
        $run = Invoke-RestMethod "$backendBase/api/v1/vacancies/relevance/parallel/$($run.id)" -TimeoutSec 20
    }
    $finished = $true
    $after = Invoke-RestMethod "$backendBase/api/v1/vacancies?resumeId=$ResumeId" -TimeoutSec 30
    $report = @{ completedAt = [DateTimeOffset]::UtcNow.ToString('o'); run = $run;
        seconds = [Math]::Round($watch.Elapsed.TotalSeconds, 2); peakRunningRelevance = $peak;
        ratingsUnchanged = ($beforeHash -eq (Get-RatingHash $after)); samples = $samples.ToArray() }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $reportPath = Join-Path $OutputDirectory ('relevance-benchmark-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    $report | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Host "Итог: $($run.status). Пик оценок: $peak. Прежние оценки не изменились: $($report.ratingsUnchanged). Отчёт: $reportPath"
    if ($run.status -ne 'done') { throw "Прогон завершился с ошибкой: $($run.errorCode): $($run.errorMessage)" }
} finally {
    $watch.Stop()
    if ($null -ne $run -and -not $finished) {
        try {
            $null = Invoke-RestMethod "$backendBase/api/v1/vacancies/relevance/parallel/$($run.id)" -Method Delete -TimeoutSec 20
        } catch { Write-Warning 'Не удалось отменить опрос. Запущенные задания могут продолжить работу; контрольный режим не записывает оценки.' }
    }
}
