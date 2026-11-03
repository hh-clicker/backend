package org.hhclicker.backend.market;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.hhclicker.backend.market.MarketAnalysisRepository.*;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MarketAnalysisService {
    private static final int EXTRACTION_BATCH = 8;
    private static final int COVERAGE_BATCH = 30;
    private final MarketAnalysisRepository repository;
    private final ResumeRepository resumes;
    private final MarketGenerationClient agent;
    private final MarketAnalysisCoordinator parallel;
    private final Map<UUID, Operation> operations = new HashMap<>();

    private static final class Operation {
        private final Run snapshot;
        private final boolean resuming;
        private boolean invalidated;

        private Operation(Run snapshot, boolean resuming) {
            this.snapshot = snapshot;
            this.resuming = resuming;
        }
    }

    @Autowired
    public MarketAnalysisService(MarketAnalysisRepository repository, ResumeRepository resumes, MarketGenerationClient agent,
                                 MarketAnalysisCoordinator parallel) {
        this.repository = repository;
        this.resumes = resumes;
        this.agent = agent;
        this.parallel = parallel;
    }

    public record Summary(UUID id, UUID resumeId, String resumeName, Instant createdAt, String status,
                          String phase, int processed, int vacancyCount, int companyCount,
                          Instant periodFrom, Instant periodTo, Integer coveragePercent,
                          boolean reportAvailable, String error, boolean diagnosticOnly, int diagnosticCount,
                          Long estimatedRemainingSeconds, String searchName) { }

    public record Preview(int availableCount, int selectedCount, Instant earliestAttemptedAt) { }
    public record NotificationRun(UUID id, UUID accountId, UUID resumeId, String resumeName,
                                  String status, String phase, String revision, Instant updatedAt,
                                  int processed, int total, String error) { }

    public List<NotificationRun> notificationRuns() {
        return repository.notificationRuns().stream().map(run -> new NotificationRun(run.id(), run.accountId(),
                run.resumeId(), run.resumeName(), run.status(), run.phase(), run.revision(), run.updatedAt(),
                run.processed(), run.total(),
                run.error())).toList();
    }

    public Preview preview(UUID resumeId, Integer maxVacancies) {
        return preview(resumeId, maxVacancies, null);
    }

    public Preview preview(UUID resumeId, Integer maxVacancies, UUID searchId) {
        return preview(resumeId, maxVacancies, searchId, null);
    }

    public Preview preview(UUID resumeId, Integer maxVacancies, UUID searchId, MarketRelevanceCategory relevanceCategory) {
        requireResume(resumeId);
        requireSearch(resumeId, searchId);
        List<Source> all = sourcesFor(resumeId, searchId, relevanceCategory);
        List<Source> selected = selectLatest(all, maxVacancies);
        return new Preview(all.size(), selected.size(), selected.stream().map(Source::attemptedAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null));
    }

    public synchronized Summary start(UUID resumeId) {
        return start(resumeId, null);
    }

    public synchronized Summary start(UUID resumeId, Integer maxVacancies) {
        return start(resumeId, maxVacancies, null);
    }

    public synchronized Summary start(UUID resumeId, Integer maxVacancies, UUID searchId) {
        return start(resumeId, maxVacancies, searchId, null);
    }

    public synchronized Summary start(UUID resumeId, Integer maxVacancies, UUID searchId,
                                      MarketRelevanceCategory relevanceCategory) {
        var resume = requireResume(resumeId);
        String searchName = requireSearch(resumeId, searchId);
        if (repository.hasOpen(resumeId, null) || resumeReserved(resumeId)) throw new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_ALREADY_RUNNING", "Анализ этого резюме уже выполняется.");
        List<Source> sources = selectLatest(sourcesFor(resumeId, searchId, relevanceCategory), maxVacancies);
        if (sources.isEmpty()) throw new ApplicationException(ApplicationErrorCategory.CONFLICT,
                "MARKET_ANALYSIS_NO_SOURCES", "Нет вакансий с сохранённым полным текстом.");
        Run run = new Run(UUID.randomUUID(), resumeId, resume.name(), "", Instant.now(),
                "RUNNING", "EXTRACT", 0, null, sources, List.of(), List.of(), List.of(), null, null,
                null, 0, searchId, searchName);
        parallel.initialize(run);
        return summary(run);
    }

    private org.hhclicker.backend.resume.model.HhResume requireResume(UUID resumeId) {
        return resumes.findById(resumeId).orElseThrow(() -> new ApplicationException(
                ApplicationErrorCategory.NOT_FOUND, "RESUME_NOT_FOUND", "Резюме не найдено."));
    }

    private String requireSearch(UUID resumeId, UUID searchId) {
        if (searchId == null) return null;
        return repository.searchName(resumeId, searchId).orElseThrow(() -> new ApplicationException(
                ApplicationErrorCategory.NOT_FOUND, "MARKET_ANALYSIS_SEARCH_NOT_FOUND",
                "Поиск для выбранного резюме не найден."));
    }

    private List<Source> sourcesFor(UUID resumeId, UUID searchId, MarketRelevanceCategory relevanceCategory) {
        if (relevanceCategory != null) return repository.sources(resumeId, searchId, relevanceCategory);
        return searchId == null ? repository.sources(resumeId) : repository.sources(resumeId, searchId);
    }

    private List<Source> selectLatest(List<Source> sources, Integer maxVacancies) {
        if (maxVacancies == null) return sources;
        if (maxVacancies <= 0) throw new ApplicationException(ApplicationErrorCategory.VALIDATION,
                "MARKET_ANALYSIS_INVALID_LIMIT", "Количество вакансий должно быть положительным.");
        return sources.subList(Math.max(0, sources.size() - maxVacancies), sources.size());
    }

    public synchronized Summary stop(UUID id) {
        if (parallel.owns(id)) return summary(parallel.stop(id));
        Run run = load(id);
        Operation operation = operations.get(id);
        if ("PAUSED".equals(run.status()) && operation != null && operation.resuming) {
            operation.invalidated = true;
            return summary(run);
        }
        if ("STOPPING".equals(run.status()) || "PAUSED".equals(run.status())) return summary(run);
        if (!"RUNNING".equals(run.status()) || !"EXTRACT".equals(run.phase())) throw new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_NOT_STOPPABLE", "Этот анализ нельзя остановить.");
        if (run.position() == run.sources().size()) {
            Run completed = completed(run);
            repository.save(completed);
            return summary(completed);
        }
        Run stopped = with(run, run.jobId() == null && operation == null ? "PAUSED" : "STOPPING", run.phase(), run.position(),
                run.jobId(), run.facts(), run.coverage(), null, null);
        repository.save(stopped);
        return summary(stopped);
    }

    public synchronized Summary cancel(UUID id) {
        if (parallel.owns(id)) return summary(parallel.cancel(id));
        Run run = load(id);
        if ("CANCELLED".equals(run.status())) return summary(run);
        if (!Set.of("RUNNING", "STOPPING", "PAUSED").contains(run.status())) throw new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_NOT_CANCELLABLE", "Этот анализ нельзя отменить.");
        // Generation Agent has no cancellation endpoint. Its in-flight result is ignored.
        Run cancelled = with(run, "CANCELLED", run.phase(), run.position(), null,
                run.facts(), run.coverage(), null, null);
        repository.save(cancelled);
        Operation operation = operations.get(id);
        if (operation != null) operation.invalidated = true;
        return summary(cancelled);
    }

    public Summary resume(UUID id) {
        Operation operation;
        synchronized (this) {
            Run run = load(id);
            validateResume(run);
            if (parallel.owns(id)) return summary(parallel.resume(id));
            if (operations.containsKey(id) || resumeReserved(run.resumeId())) throw new ApplicationException(
                    ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_ALREADY_RUNNING",
                    "Операция с этим анализом уже выполняется.");
            operation = new Operation(run, true);
            operations.put(id, operation);
        }
        try {
            Run run = operation.snapshot;
            UUID jobId = resumeJob(run);
            synchronized (this) {
                Run current = load(id);
                if (operation.invalidated || !current.equals(run)) return summary(current);
                Run resumed = withEstimateEpoch(with(run, "RUNNING", "EXTRACT", run.position(), jobId,
                        run.facts(), run.coverage(), null, null), Instant.now());
                repository.save(resumed);
                return summary(resumed);
            }
        } finally {
            release(operation);
        }
    }

    private boolean resumeReserved(UUID resumeId) {
        return operations.values().stream().anyMatch(operation -> operation.resuming && !operation.invalidated
                && operation.snapshot.resumeId().equals(resumeId));
    }

    private void validateResume(Run run) {
        if (!("FAILED".equals(run.status()) || "PAUSED".equals(run.status())) || !"EXTRACT".equals(run.phase())) throw new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_NOT_RESUMABLE", "Этот анализ нельзя продолжить.");
        if (Duration.between(run.createdAt(), Instant.now()).toDays() >= 7) throw new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_EXPIRED", "Снимок анализа старше семи дней. Запустите новый анализ.");
        if (repository.hasOpen(run.resumeId(), run.id())) throw new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_ALREADY_RUNNING", "Анализ этого резюме уже выполняется.");
    }

    private UUID resumeJob(Run run) {
        UUID jobId = null;
        if ("FAILED".equals(run.status()) && run.position() < run.sources().size()) {
            if (run.jobId() != null) {
                try {
                    var previous = agent.extraction(run.jobId());
                    if (previous != null && run.jobId().equals(previous.id())
                            && ("queued".equals(previous.status()) || "running".equals(previous.status())
                                || ("done".equals(previous.status()) && previous.result() != null)))
                        jobId = run.jobId();
                } catch (ApplicationException exception) {
                    if (!"MARKET_GENERATION_JOB_LOST".equals(exception.machineCode())) throw exception;
                }
            }
            if (jobId == null) {
                var request = extractionRequest(run);
                String keyPrefix = run.id() + "-extract-" + run.position() + "-retry-";
                var submitted = agent.extract(request, keyPrefix + (run.jobId() == null ? "legacy" : run.jobId()));
                if (submitted == null || submitted.id() == null) throw invalidResponse();
                // A previous resume may have submitted a job but failed to save its ID locally.
                if ("failed".equals(submitted.status())) {
                    submitted = agent.extract(request, keyPrefix + submitted.id());
                    if (submitted == null || submitted.id() == null) throw invalidResponse();
                }
                jobId = submitted.id();
            }
        }
        return jobId;
    }

    public List<Summary> list(UUID resumeId) {
        return repository.list(resumeId).stream().map(this::summary).toList();
    }

    public Summary find(UUID id) { return summary(load(id)); }

    public String markdown(UUID id) {
        return markdown(id, false);
    }

    public String markdown(UUID id, boolean debug) {
        Run run = load(id);
        if (!"COMPLETED".equals(run.status()) || run.markdown() == null) throw new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "MARKET_ANALYSIS_NOT_READY", "Отчёт ещё не готов.");
        if (debug) return MarketMarkdown.render(run, MarketAggregation.aggregate(run.sources(), run.facts()));
        return MarketMarkdown.renderForAgent(run,
                MarketAggregation.aggregateIgnoreCase(run.sources(), run.facts()));
    }

    private Run load(UUID id) {
        return repository.find(id).orElseThrow(() -> new ApplicationException(
                ApplicationErrorCategory.NOT_FOUND, "MARKET_ANALYSIS_NOT_FOUND", "Анализ не найден."));
    }

    private Summary summary(Run run) {
        Instant from = run.sources().stream().map(Source::attemptedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant to = run.sources().stream().map(Source::attemptedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        int estimatePosition = run.estimateStartPosition();
        var estimate = MarketAnalysisEstimate.remainingSeconds(run.status(), run.phase(),
                run.estimateStartedAt() == null ? run.createdAt() : run.estimateStartedAt(),
                Instant.now(), run.position() - estimatePosition,
                run.sources().size() - estimatePosition, EXTRACTION_BATCH);
        return new Summary(run.id(), run.resumeId(), run.resumeName(), run.createdAt(), run.status(), run.phase(),
                run.position(), run.sources().size(), (int) run.sources().stream().map(Source::employerKey).distinct().count(),
                from, to, null, run.markdown() != null, run.error(),
                "COMPLETED".equals(run.status()) && run.coverage().isEmpty(), run.diagnostics().size(),
                estimate.isPresent() ? estimate.getAsLong() : null, run.searchName());
    }

    @Scheduled(fixedDelay = 5000)
    public void advanceActive() {
        for (Run candidate : repository.active()) {
            if (parallel.owns(candidate.id())) {
                parallel.advance(candidate.id());
                continue;
            }
            Operation operation;
            synchronized (this) {
                if (operations.containsKey(candidate.id())) continue;
                Run current = load(candidate.id());
                if (!Set.of("RUNNING", "STOPPING").contains(current.status())) continue;
                operation = new Operation(current, false);
                operations.put(current.id(), operation);
            }
            try {
                Run run = operation.snapshot;
                Run next;
                try {
                    next = Duration.between(run.createdAt(), Instant.now()).toDays() >= 7
                            ? failed(run, "Срок выполнения анализа истёк. Запустите новый анализ.") : advance(run);
                } catch (ApplicationException exception) {
                    next = exception.category() == ApplicationErrorCategory.EXTERNAL_SYSTEM
                            ? null : failed(run, exception.safeMessage());
                } catch (RuntimeException exception) {
                    next = failed(run, "Не удалось обработать ответ анализа. Запустите новый анализ.");
                }
                // Persistence failures must not be classified as invalid Generation Agent responses.
                if (next != null) publish(operation, next);
            } finally {
                release(operation);
            }
        }
    }

    private synchronized void release(Operation operation) {
        operations.remove(operation.snapshot.id(), operation);
    }

    private synchronized void publish(Operation operation, Run next) {
        if (operation.invalidated) return;
        Run expected = operation.snapshot;
        Run current = load(expected.id());
        boolean stopping = "RUNNING".equals(expected.status()) && "STOPPING".equals(current.status())
                && expected.equals(with(current, "RUNNING", current.phase(), current.position(), current.jobId(),
                current.facts(), current.coverage(), current.markdown(), current.error()));
        if (!current.equals(expected) && !stopping) return;
        if (stopping && "RUNNING".equals(next.status())) {
            if ("EXTRACT".equals(next.phase()) && next.jobId() == null && next.position() == next.sources().size()) {
                next = completed(next);
            } else {
                next = with(next, next.jobId() == null ? "PAUSED" : "STOPPING", next.phase(), next.position(),
                        next.jobId(), next.facts(), next.coverage(), next.markdown(), next.error());
            }
        }
        repository.save(next);
    }

    private Run advance(Run run) {
        if ("EXTRACT".equals(run.phase())) return extract(run);
        if ("COVERAGE".equals(run.phase())) return cover(run);
        return null;
    }

    private Run extract(Run run) {
        if (run.position() >= run.sources().size()) {
            // Current flow ends after extraction; resume comparison is delegated to the report reader.
            return completed(run);
        }
        if ("STOPPING".equals(run.status()) && run.jobId() == null) {
            return with(run, "PAUSED", "EXTRACT", run.position(), null,
                    run.facts(), run.coverage(), null, null);
        }
        List<Source> batch = run.sources().subList(run.position(), Math.min(run.position() + EXTRACTION_BATCH, run.sources().size()));
        if (run.jobId() == null) {
            var submitted = agent.extract(extractionRequest(run), run.id() + "-extract-" + run.position());
            if (submitted == null || submitted.id() == null) throw invalidResponse();
            return with(run, "RUNNING", "EXTRACT", run.position(), submitted.id(), run.facts(), run.coverage(), null, null);
        }
        var job = agent.extraction(run.jobId());
        if (job == null || !run.jobId().equals(job.id())) throw invalidResponse();
        if ("queued".equals(job.status()) || "running".equals(job.status())) return null;
        if ("failed".equals(job.status())) return failed(run, "Не удалось разобрать вакансии: " + safeAgentError(job.error()));
        var accepted = MarketExtractionResults.validate(batch, job);
        List<Fact> facts = new ArrayList<>(run.facts());
        facts.addAll(accepted.facts());
        List<Diagnostic> diagnostics = new ArrayList<>(run.diagnostics());
        diagnostics.addAll(accepted.diagnostics());
        int nextPosition = run.position() + batch.size();
        if ("STOPPING".equals(run.status()) && nextPosition == run.sources().size()) {
            Run finished = with(run, "STOPPING", "EXTRACT", nextPosition, null,
                    List.copyOf(facts), run.coverage(), List.copyOf(diagnostics), null, null);
            return completed(finished);
        }
        String nextStatus = "STOPPING".equals(run.status()) ? "PAUSED" : "RUNNING";
        return with(run, nextStatus, "EXTRACT", nextPosition, null,
                List.copyOf(facts), run.coverage(), List.copyOf(diagnostics), null, null);
    }

    private MarketGenerationClient.ExtractionRequest extractionRequest(Run run) {
        List<Source> batch = run.sources().subList(run.position(), Math.min(run.position() + EXTRACTION_BATCH, run.sources().size()));
        return new MarketGenerationClient.ExtractionRequest(batch.stream().map(item ->
                new MarketGenerationClient.VacancyInput(item.id(), item.title(),
                        item.text().substring(0, Math.min(item.text().length(), 30_000)))).toList());
    }

    private Run completed(Run run) {
        return with(run, "COMPLETED", "DONE", run.position(), null, run.facts(), run.coverage(),
                MarketMarkdown.renderForAgent(run,
                        MarketAggregation.aggregateIgnoreCase(run.sources(), run.facts())), null);
    }

    private Run cover(Run run) {
        var groups = MarketAggregation.aggregate(run.sources(), run.facts());
        if (run.position() >= groups.size()) {
            String markdown = MarketMarkdown.render(run, groups);
            return with(run, "COMPLETED", "DONE", groups.size(), null, run.facts(), run.coverage(), markdown, null);
        }
        var batch = groups.subList(run.position(), Math.min(run.position() + COVERAGE_BATCH, groups.size()));
        if (run.jobId() == null) {
            var request = new MarketGenerationClient.CoverageRequest(run.resumeText(), batch.stream()
                    .map(group -> new MarketGenerationClient.RequirementInput(group.id(), group.name())).toList());
            var submitted = agent.cover(request, run.id() + "-coverage-" + run.position());
            if (submitted == null || submitted.id() == null) throw invalidResponse();
            return with(run, "RUNNING", "COVERAGE", run.position(), submitted.id(), run.facts(), run.coverage(), null, null);
        }
        var job = agent.coverage(run.jobId());
        if (job == null || !run.jobId().equals(job.id())) throw invalidResponse();
        if ("queued".equals(job.status()) || "running".equals(job.status())) return null;
        if ("failed".equals(job.status())) return failed(run, "Не удалось сравнить резюме: " + safeAgentError(job.error()));
        if (!"done".equals(job.status()) || job.result() == null || job.result().items() == null || job.result().items().size() != batch.size()) throw invalidResponse();
        Set<UUID> ids = batch.stream().map(MarketAggregation.Group::id).collect(Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        List<Coverage> coverage = new ArrayList<>(run.coverage());
        List<Diagnostic> diagnostics = appendDiagnostics(run, "COVERAGE", job.result().diagnostics());
        for (var item : job.result().items()) {
            if (item == null || !ids.contains(item.id()) || !seen.add(item.id()) || item.status() == null || item.evidence() == null
                    || !Set.of("EXPLICIT", "INDIRECT", "NOT_FOUND", "UNVERIFIED").contains(item.status())
                    || ("NOT_FOUND".equals(item.status()) && !item.evidence().isEmpty())
                    || ("UNVERIFIED".equals(item.status()) && !item.evidence().isEmpty())
                    || (Set.of("EXPLICIT", "INDIRECT").contains(item.status()) && (item.evidence().isBlank() || !run.resumeText().toLowerCase(Locale.ROOT)
                            .contains(item.evidence().toLowerCase(Locale.ROOT))))) {
                diagnostics.add(new Diagnostic("COVERAGE", item == null ? null : item.id(), "ITEM_SHAPE", "",
                        item == null ? "" : item.evidence()));
                if (item != null && ids.contains(item.id())) coverage.add(new Coverage(item.id(), "UNVERIFIED", ""));
                continue;
            }
            coverage.add(new Coverage(item.id(), item.status(), item.evidence()));
        }
        return with(run, "RUNNING", "COVERAGE", run.position() + batch.size(), null,
                run.facts(), List.copyOf(coverage), List.copyOf(diagnostics), null, null);
    }

    private List<Diagnostic> appendDiagnostics(Run run, String phase,
                                                List<MarketGenerationClient.AgentDiagnostic> reported) {
        List<Diagnostic> diagnostics = new ArrayList<>(run.diagnostics());
        if (reported != null) for (var item : reported) {
            if (item == null || item.reason() == null) continue;
            diagnostics.add(new Diagnostic(phase, item.id(), item.reason(), item.name(), item.evidence()));
        }
        return diagnostics;
    }

    private Run failed(Run run, String message) {
        return with(run, "FAILED", run.phase(), run.position(), run.jobId(),
                run.facts(), run.coverage(), null, message);
    }

    private String safeAgentError(MarketGenerationClient.AgentError error) {
        if (error == null || error.message() == null) return "ошибка Generation Agent";
        return Boolean.TRUE.equals(error.retried()) ? error.message() + " Повторная попытка тоже не помогла." : error.message();
    }

    private ApplicationException invalidResponse() {
        return new ApplicationException(ApplicationErrorCategory.INTERNAL, "MARKET_AGENT_INVALID_RESPONSE", "Некорректный ответ Generation Agent.");
    }

    private Run with(Run run, String status, String phase, int position, UUID jobId,
                     List<Fact> facts, List<Coverage> coverage, String markdown, String error) {
        return with(run, status, phase, position, jobId, facts, coverage, run.diagnostics(), markdown, error);
    }

    private Run with(Run run, String status, String phase, int position, UUID jobId,
                     List<Fact> facts, List<Coverage> coverage, List<Diagnostic> diagnostics,
                     String markdown, String error) {
        return new Run(run.id(), run.resumeId(), run.resumeName(), run.resumeText(), run.createdAt(), status,
                phase, position, jobId, run.sources(), facts, coverage, diagnostics, markdown, error,
                run.estimateStartedAt(), run.estimateStartPosition(), run.searchId(), run.searchName());
    }

    private Run withEstimateEpoch(Run run, Instant startedAt) {
        return new Run(run.id(), run.resumeId(), run.resumeName(), run.resumeText(), run.createdAt(),
                run.status(), run.phase(), run.position(), run.jobId(), run.sources(), run.facts(),
                run.coverage(), run.diagnostics(), run.markdown(), run.error(), startedAt, run.position(),
                run.searchId(), run.searchName());
    }
}
