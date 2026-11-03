#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('relevance', 'market')][string]$Kind = 'relevance',
    [guid]$RunId = [guid]::Empty,
    [uri]$BackendUrl = 'http://127.0.0.1:8080',
    [uri]$AgentUrl = 'http://127.0.0.1:17374',
    [ValidateRange(1, 60)][int]$IntervalSeconds = 5,
    [ValidateRange(1, 720)][int]$Samples = 120,
    [string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'hh-clicker-diagnostics')
)
$ErrorActionPreference = 'Stop'
foreach ($address in @($BackendUrl, $AgentUrl)) {
    if (-not $address.IsLoopback -or $address.Scheme -notin @('http', 'https') -or $address.AbsolutePath -ne '/') {
        throw 'Наблюдение разрешено только для локальных backend и Generation Agent.'
    }
}
$backendBase = $BackendUrl.AbsoluteUri.TrimEnd('/')
$agentBase = $AgentUrl.AbsoluteUri.TrimEnd('/')
if ($RunId -eq [guid]::Empty) {
    $overview = Invoke-RestMethod "$backendBase/api/v1/overview" -TimeoutSec 10
    $activeRuns = @(
        foreach ($resume in $overview.resumes) {
            if ($Kind -eq 'market') {
                Invoke-RestMethod "$backendBase/api/v1/market-analyses?resumeId=$($resume.id)" -TimeoutSec 10 |
                    ForEach-Object { $_ } | Where-Object status -In @('RUNNING', 'STOPPING')
            } else {
                $active = Invoke-RestMethod "$backendBase/api/v1/vacancies/relevance/parallel/active?resumeId=$($resume.id)" -TimeoutSec 10
                if ($active.id) { $active }
            }
        }
    )
    if ($activeRuns.Count -ne 1) { throw 'Нужен один активный запуск или явный -RunId <UUID>.' }
    $RunId = [guid]$activeRuns[0].id
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$outputPath = Join-Path $OutputDirectory ($Kind + '-metrics-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.jsonl')
$parentPath = if ($Kind -eq 'market') { "/api/v1/market-analyses/$RunId" } else { "/api/v1/vacancies/relevance/parallel/$RunId" }
Write-Host "Наблюдение за $RunId. JSONL: $outputPath"
for ($sample = 0; $sample -lt $Samples; $sample++) {
    $metrics = Invoke-RestMethod "$agentBase/metrics?runId=$RunId" -TimeoutSec 15
    $parent = Invoke-RestMethod "$backendBase$parentPath" -TimeoutSec 10
    $metrics | Add-Member -NotePropertyName parent -NotePropertyValue @{ status = $parent.status
        processed = if ($Kind -eq 'market') { $parent.processed } else { $parent.evaluatedCount }
        total = if ($Kind -eq 'market') { $parent.vacancyCount } else { $parent.requestedCount } }
    if ($metrics.version -ne 1 -or $null -eq $metrics.summary) { throw 'Неизвестный формат метрик агента.' }
    $metrics | ConvertTo-Json -Depth 25 -Compress | Add-Content -LiteralPath $outputPath -Encoding utf8
    $states = $metrics.summary.states
    $stages = @($metrics.jobs | Where-Object status -eq 'running' | Group-Object { if ($_.telemetry) { $_.telemetry.stage } else { 'unavailable' } } |
        ForEach-Object { "$($_.Name):$($_.Count)" }) -join ', '
    $errors = @($metrics.summary.errors.PSObject.Properties | ForEach-Object { "$($_.Name):$($_.Value)" }) -join ', '
    $silent = @($metrics.jobs | Where-Object status -eq 'running' |
        ForEach-Object { $_.telemetry.cli.silentForMs } | Where-Object { $null -ne $_ })
    $maxSilence = if ($silent.Count) { [Math]::Round(($silent | Measure-Object -Maximum).Maximum / 1000, 1) } else { $null }
    Write-Host ("{0} done={1}, failed={2}, running={3}, queued={4}; этапы [{5}]; тишина CLI max={6} с; ошибки [{7}]" -f `
        (Get-Date -Format 'HH:mm:ss'), $states.done, $states.failed, $states.running, $states.queued, $stages, $maxSilence, $errors)
    if ($metrics.truncated) { Write-Warning 'Список подробностей ограничен 200 заданиями; сводка учитывает все.' }
    # A rolling market window may temporarily have no remote work before the next checkpoint/claim.
    if ($parent.status -in @('COMPLETED', 'FAILED', 'PAUSED', 'CANCELLED', 'done', 'failed', 'cancelled') -and
        $states.running -eq 0 -and $states.queued -eq 0) { break }
    if ($sample -lt $Samples - 1) { Start-Sleep -Seconds $IntervalSeconds }
}
Write-Host "Метрики сохранены: $outputPath"
