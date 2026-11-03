package org.hhclicker.backend.vacancy.relevance;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.function.LongSupplier;

/** One virtual thread per batch, with no semaphore or CPU-sized executor. No HH calls. */
@Service
public class ParallelVacancyRelevanceService {
    private static final int BATCH_SIZE = 10;
    private static final int BATCH_TEXT_CHARS = 30_000;
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final int HISTORY_LIMIT = 100;
    private final VacancyRepository vacancies;
    private final ResumeRepository resumes;
    private final GenerationAgentRelevanceClient agent;
    private final VacancyRelevancePersistenceService persistence;
    private final VacancyRelevanceService relevance;
    private final Clock clock;
    private final ExecutorService executor;
    private final Duration pollDelay;
    private final Duration batchDeadline;
    private final LongSupplier nanoTime;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    @Autowired
    public ParallelVacancyRelevanceService(VacancyRepository vacancies, ResumeRepository resumes,
            GenerationAgentRelevanceClient agent, VacancyRelevancePersistenceService persistence,
            VacancyRelevanceService relevance, Clock clock) {
        this(vacancies, resumes, agent, persistence, relevance, clock,
                Executors.newVirtualThreadPerTaskExecutor(), Duration.ofSeconds(1), Duration.ofMinutes(20));
    }

    ParallelVacancyRelevanceService(VacancyRepository vacancies, ResumeRepository resumes,
            GenerationAgentRelevanceClient agent, VacancyRelevancePersistenceService persistence,
            VacancyRelevanceService relevance, Clock clock, ExecutorService executor,
            Duration pollDelay, Duration batchDeadline) {
        this(vacancies, resumes, agent, persistence, relevance, clock, executor, pollDelay, batchDeadline, System::nanoTime);
    }

    ParallelVacancyRelevanceService(VacancyRepository vacancies, ResumeRepository resumes,
            GenerationAgentRelevanceClient agent, VacancyRelevancePersistenceService persistence,
            VacancyRelevanceService relevance, Clock clock, ExecutorService executor,
            Duration pollDelay, Duration batchDeadline, LongSupplier nanoTime) {
        this.vacancies = vacancies;
        this.resumes = resumes;
        this.agent = agent;
        this.persistence = persistence;
        this.relevance = relevance;
        this.clock = clock;
        this.executor = executor;
        this.pollDelay = pollDelay;
        this.batchDeadline = batchDeadline;
        this.nanoTime = nanoTime;
    }

    public ParallelVacancyRelevanceResponse start(ParallelVacancyRelevanceRequest request) {
        cleanup();
        if (stopping.get()) throw failure("VACANCY_RELEVANCE_STOPPING", "Сервис оценки останавливается.");
        List<UUID> ids = request.vacancyIds();
        if (new HashSet<>(ids).size() != ids.size()) throw validation("VACANCY_RELEVANCE_DUPLICATE_IDS", "Список вакансий содержит повторяющиеся ID.");
        List<Vacancy> found = vacancies.findAllByIds(ids);
        if (found.size() != ids.size()) throw validation("VACANCY_RELEVANCE_VACANCY_NOT_FOUND", "Одна или несколько вакансий не найдены.");
        Set<UUID> resumeIds = found.stream().map(Vacancy::resumeId).collect(Collectors.toSet());
        if (resumeIds.size() != 1) throw validation("VACANCY_RELEVANCE_MIXED_RESUMES", "Оценка должна относиться к одному резюме.");
        boolean benchmark = Boolean.TRUE.equals(request.benchmark());
        List<Vacancy> pending = found.stream().filter(item -> benchmark || item.relevance() == null).toList();
        if (pending.isEmpty()) throw new ApplicationException(ApplicationErrorCategory.CONFLICT,
                "VACANCY_RELEVANCE_ALREADY_DONE", "Все выбранные вакансии уже оценены.");
        UUID resumeId = resumeIds.iterator().next();
        int before = benchmark || request.evaluatedBefore() == null ? 0 : request.evaluatedBefore();
        int total = benchmark || request.totalCount() == null ? pending.size() : request.totalCount();
        if (before < 0 || total < 1 || before > total || (long) before + pending.size() > total) throw validation("VACANCY_RELEVANCE_INVALID_PROGRESS", "Некорректные параметры общего прогресса оценки.");
        relevance.reserveStart(resumeId);
        Run run;
        List<List<GenerationAgentRelevanceClient.VacancyInput>> batches;
        String profile;
        try {
            var resume = resumes.findById(resumeId).orElseThrow(() -> new ResumeNotFoundException(resumeId));
            if (resume.plainText() == null || resume.plainText().isBlank()) throw new ApplicationException(
                    ApplicationErrorCategory.CONFLICT, "RESUME_TEXT_REQUIRED", "Сначала загрузите текст резюме.");
            profile = "Название резюме: %s\n\n%s".formatted(resume.name(), resume.plainText());
            batches = split(pending.stream().map(relevance::toInput).toList());
            Map<String, UUID> byHh = pending.stream().collect(Collectors.toUnmodifiableMap(Vacancy::hhVacancyId, Vacancy::id));
            run = new Run(UUID.randomUUID(), resume.accountId(), resumeId, resume.name(), pending.size(),
                    before, total, batches.size(), benchmark, byHh, clock.instant());
            runs.put(run.id, run);
        } catch (RuntimeException exception) {
            relevance.releaseStart(resumeId);
            throw exception;
        }
        for (int index = 0; index < batches.size(); index++) {
            var batch = batches.get(index);
            String key = "relevance-" + run.id + "-" + index;
            String snapshot = profile;
            try {
                executor.execute(() -> evaluate(run, batch, snapshot, key));
            } catch (RejectedExecutionException exception) {
                finish(run, 0, "VACANCY_RELEVANCE_INTERRUPTED", "Оценка прервана при остановке backend.");
            }
        }
        return run.snapshot();
    }

    static List<List<GenerationAgentRelevanceClient.VacancyInput>> split(List<GenerationAgentRelevanceClient.VacancyInput> inputs) {
        List<List<GenerationAgentRelevanceClient.VacancyInput>> result = new ArrayList<>();
        List<GenerationAgentRelevanceClient.VacancyInput> batch = new ArrayList<>();
        int characters = 0;
        for (var input : inputs) {
            int length = input.plainText().length();
            if (!batch.isEmpty() && (batch.size() == BATCH_SIZE || characters + length > BATCH_TEXT_CHARS)) {
                result.add(List.copyOf(batch));
                batch.clear();
                characters = 0;
            }
            batch.add(input);
            characters += length;
        }
        if (!batch.isEmpty()) result.add(List.copyOf(batch));
        return List.copyOf(result);
    }

    private void evaluate(Run run, List<GenerationAgentRelevanceClient.VacancyInput> batch, String profile, String key) {
        int count = 0;
        String code = "VACANCY_RELEVANCE_PROCESSING_FAILED";
        String message = "Не удалось обработать партию оценки.";
        try {
            if (stopping.get() || run.cancelled) throw new InterruptedException();
            // No retry of an uncertain POST: polling and persistence retries never create another model job.
            var submitted = agent.submit(new GenerationAgentRelevanceClient.GenerationRequest(profile, batch), key);
            if (submitted == null || submitted.id() == null || submitted.status() == null
                    || !Set.of("queued", "running", "done", "failed").contains(submitted.status())) throw invalidResponse();
            Map<String, UUID> batchIds = batch.stream().collect(Collectors.toUnmodifiableMap(
                    GenerationAgentRelevanceClient.VacancyInput::hhId, item -> run.byHh.get(item.hhId())));
            long lastContact = nanoTime.getAsLong();
            Long runningStarted = null;
            boolean persistenceFailed = false;
            while (true) {
                if (stopping.get() || run.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long now = nanoTime.getAsLong();
                if (now - lastContact >= batchDeadline.toNanos()
                        || (runningStarted != null && now - runningStarted >= batchDeadline.toNanos())) throw failure(persistenceFailed
                                ? "VACANCY_RELEVANCE_PERSISTENCE_FAILED" : "VACANCY_RELEVANCE_TIMEOUT",
                        persistenceFailed ? "Не удалось сохранить результаты партии." : "Истекло время ожидания оценки партии.");
                try {
                    var job = agent.find(submitted.id());
                    if (job == null || !submitted.id().equals(job.id()) || job.status() == null) throw invalidResponse();
                    lastContact = nanoTime.getAsLong();
                    if (runningStarted == null && Set.of("running", "done").contains(job.status())) runningStarted = lastContact;
                    if ("failed".equals(job.status())) throw failure(
                            job.error() == null || job.error().code() == null ? "GENERATION_FAILED" : job.error().code(),
                            job.error() == null || job.error().message() == null ? "Не удалось оценить партию вакансий." : job.error().message());
                    if ("done".equals(job.status())) {
                        var evaluations = relevance.validatedEvaluations(batchIds, job.result());
                        try {
                            // Cancellation and the short DB transaction are serialized, never an HTTP call.
                            synchronized (run) {
                                if (stopping.get() || run.cancelled) throw new InterruptedException();
                                if (!run.benchmark) persistence.save(evaluations);
                            }
                        } catch (RuntimeException exception) {
                            persistenceFailed = true;
                            // Re-read the same completed job, without a second generation or double progress.
                            Thread.sleep(pollDelay.toMillis());
                            continue;
                        }
                        count = evaluations.size();
                        code = null;
                        message = null;
                        break;
                    }
                    if (!Set.of("queued", "running").contains(job.status())) throw invalidResponse();
                } catch (ApplicationException exception) {
                    if (!"GENERATION_AGENT_UNAVAILABLE".equals(exception.machineCode())) throw exception;
                }
                Thread.sleep(pollDelay.toMillis());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            code = "VACANCY_RELEVANCE_INTERRUPTED";
            message = "Оценка прервана при остановке backend.";
        } catch (ApplicationException exception) {
            code = exception.machineCode();
            message = exception.safeMessage();
        } catch (RuntimeException exception) {
            code = "VACANCY_RELEVANCE_PROCESSING_FAILED";
            message = "Не удалось обработать партию оценки.";
        } finally {
            finish(run, count, code, message);
        }
    }

    private void finish(Run run, int count, String code, String message) {
        synchronized (run) {
            if (run.finish(count, code, message, clock.instant())) relevance.releaseStart(run.resumeId);
        }
    }

    public ParallelVacancyRelevanceResponse find(UUID id) {
        return requireRun(id).snapshot();
    }

    private Run requireRun(UUID id) {
        cleanup();
        Run run = runs.get(id);
        if (run == null) throw new ApplicationException(ApplicationErrorCategory.NOT_FOUND,
                "VACANCY_RELEVANCE_RUN_NOT_FOUND", "Оценка не найдена или backend был перезапущен.");
        return run;
    }

    public ParallelVacancyRelevanceResponse active(UUID resumeId) {
        return runs.values().stream().filter(run -> !run.benchmark && run.resumeId.equals(resumeId))
                .map(Run::snapshot).filter(run -> Set.of("queued", "running").contains(run.status()))
                .findFirst().orElse(null);
    }

    public ParallelVacancyRelevanceResponse cancel(UUID id) {
        Run run = requireRun(id);
        synchronized (run) {
            if (run.finished < run.batches) {
                run.cancelled = true;
                run.errorCode = "VACANCY_RELEVANCE_CANCELLED";
                run.errorMessage = "Оценка отменена. Уже запущенные задания Codex могут завершиться, но их результаты не применяются.";
            }
            return run.snapshot();
        }
    }

    public List<VacancyRelevanceNotificationRun> notificationRuns() {
        cleanup();
        return runs.values().stream().map(Run::notification).filter(Objects::nonNull).toList();
    }

    private synchronized void cleanup() {
        Instant now = clock.instant();
        runs.values().removeIf(run -> run.completedAt != null && !now.isBefore(run.completedAt.plus(RETENTION)));
        runs.values().stream().filter(run -> run.completedAt != null)
                .sorted(Comparator.comparing((Run run) -> run.completedAt).reversed()).skip(HISTORY_LIMIT)
                .forEach(run -> runs.remove(run.id, run));
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private ApplicationException invalidResponse() {
        return failure("INVALID_GENERATION_AGENT_RESPONSE", "Generation Agent вернул некорректный результат оценки.");
    }

    private ApplicationException failure(String code, String message) {
        return new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM, code, message);
    }

    private ApplicationException validation(String code, String message) {
        return new ApplicationException(ApplicationErrorCategory.VALIDATION, code, message);
    }

    private static final class Run {
        private final UUID id, accountId, resumeId;
        private final String resumeName;
        private final int requested, before, total, batches;
        private final boolean benchmark;
        private final Map<String, UUID> byHh;
        private final Instant createdAt;
        private volatile Instant completedAt;
        private volatile boolean cancelled;
        private int finished, evaluated;
        private String errorCode, errorMessage;

        private Run(UUID id, UUID accountId, UUID resumeId, String resumeName, int requested, int before,
                    int total, int batches, boolean benchmark, Map<String, UUID> byHh, Instant createdAt) {
            this.id = id; this.accountId = accountId; this.resumeId = resumeId; this.resumeName = resumeName;
            this.requested = requested; this.before = before; this.total = total; this.batches = batches;
            this.benchmark = benchmark; this.byHh = byHh; this.createdAt = createdAt;
        }

        private synchronized boolean finish(int count, String code, String message, Instant now) {
            evaluated += count;
            finished++;
            if (errorCode == null && code != null) { errorCode = code; errorMessage = message; }
            if (finished == batches) { completedAt = now; return true; }
            return false;
        }

        private synchronized ParallelVacancyRelevanceResponse snapshot() {
            String status = "running";
            if (cancelled || (finished == batches && errorCode != null)) status = "failed";
            else if (finished == batches) status = "done";
            return new ParallelVacancyRelevanceResponse(id, resumeId, status, requested, evaluated, requested - evaluated,
                    errorCode, errorMessage, before, total, batches, finished, benchmark);
        }

        private synchronized VacancyRelevanceNotificationRun notification() {
            if (benchmark || cancelled) return null;
            var snapshot = snapshot();
            return new VacancyRelevanceNotificationRun(id, accountId, resumeId, resumeName, snapshot.status(),
                    createdAt + "-" + finished, true, total, before + evaluated, total - before - evaluated, errorMessage);
        }
    }
}
