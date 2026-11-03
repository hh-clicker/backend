package org.hhclicker.backend.vacancy.relevance;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class VacancyRelevanceService {

    private static final Duration TERMINAL_RETENTION = Duration.ofHours(24);
    private static final int TERMINAL_HISTORY_LIMIT = 100;

    private final VacancyRepository vacancies;
    private final ResumeRepository resumes;
    private final GenerationAgentRelevanceClient generationAgent;
    private final VacancyRelevancePersistenceService persistence;
    private final Clock clock;
    private final Map<UUID, RunContext> runs = new ConcurrentHashMap<>();
    private final Set<UUID> startingResumes = new HashSet<>();

    public VacancyRelevanceService(
            VacancyRepository vacancies,
            ResumeRepository resumes,
            GenerationAgentRelevanceClient generationAgent,
            VacancyRelevancePersistenceService persistence,
            Clock clock
    ) {
        this.vacancies = vacancies;
        this.resumes = resumes;
        this.generationAgent = generationAgent;
        this.persistence = persistence;
        this.clock = clock;
    }

    public VacancyRelevanceRunResponse start(VacancyRelevanceRequest request) {
        cleanupRuns();
        List<UUID> requestedIds = request.vacancyIds();
        if (new HashSet<>(requestedIds).size() != requestedIds.size()) {
            throw validation("VACANCY_RELEVANCE_DUPLICATE_IDS", "Список вакансий содержит повторяющиеся ID.");
        }
        List<Vacancy> found = vacancies.findAllByIds(requestedIds);
        if (found.size() != requestedIds.size()) {
            throw validation("VACANCY_RELEVANCE_VACANCY_NOT_FOUND", "Одна или несколько вакансий не найдены.");
        }
        Set<UUID> resumeIds = found.stream().map(Vacancy::resumeId).collect(Collectors.toSet());
        if (resumeIds.size() != 1) {
            throw validation("VACANCY_RELEVANCE_MIXED_RESUMES", "Одна пачка должна относиться к одному резюме.");
        }
        List<Vacancy> pending = found.stream().filter(vacancy -> vacancy.relevance() == null).toList();
        if (pending.isEmpty()) {
            throw new ApplicationException(ApplicationErrorCategory.CONFLICT, "VACANCY_RELEVANCE_ALREADY_DONE",
                    "Все выбранные вакансии уже оценены.");
        }
        UUID resumeId = resumeIds.iterator().next();
        boolean finalBatch = request.finalBatch() == null || request.finalBatch();
        int totalCount = request.totalCount() == null ? pending.size() : request.totalCount();
        int evaluatedBefore = request.evaluatedBefore() == null ? 0 : request.evaluatedBefore();
        if (evaluatedBefore > totalCount || evaluatedBefore + pending.size() > totalCount) {
            throw validation("VACANCY_RELEVANCE_INVALID_PROGRESS", "Некорректные параметры общего прогресса оценки.");
        }
        reserveStart(resumeId);
        try {
            var resume = resumes.findById(resumeId).orElseThrow(() -> new ResumeNotFoundException(resumeId));
            if (resume.plainText() == null || resume.plainText().isBlank()) {
                throw new ApplicationException(ApplicationErrorCategory.CONFLICT, "RESUME_TEXT_REQUIRED",
                        "Сначала загрузите текст резюме.");
            }
            var inputs = pending.stream().map(this::toInput).toList();
            String resumeProfile = "Название резюме: %s\n\n%s".formatted(resume.name(), resume.plainText());
            var submitted = generationAgent.submit(new GenerationAgentRelevanceClient.GenerationRequest(resumeProfile, inputs));
            if (submitted == null || submitted.id() == null
                    || !("queued".equals(submitted.status()) || "running".equals(submitted.status()))) {
                throw invalidAgentResponse();
            }
            Map<String, UUID> idsByHhId = pending.stream().collect(Collectors.toUnmodifiableMap(Vacancy::hhVacancyId, Vacancy::id));
            synchronized (this) {
                cleanupRuns();
                runs.put(submitted.id(), new RunContext(submitted.id(), resume.accountId(), resumeId, resume.name(),
                        idsByHhId, submitted.status(), finalBatch, totalCount, evaluatedBefore, clock.instant()));
            }
            return response(submitted.id(), resumeId, submitted.status(), pending.size(), 0, null, null);
        } finally {
            releaseStart(resumeId);
        }
    }

    void reserveStart(UUID resumeId) {
        synchronized (this) {
            if (startingResumes.contains(resumeId)
                    || runs.values().stream().anyMatch(run -> run.resumeId.equals(resumeId) && run.terminal == null)) {
                throw new ApplicationException(ApplicationErrorCategory.CONFLICT, "VACANCY_RELEVANCE_ALREADY_RUNNING",
                        "Для этого резюме уже выполняется оценка вакансий.");
            }
            startingResumes.add(resumeId);
        }
    }

    synchronized void releaseStart(UUID resumeId) {
        startingResumes.remove(resumeId);
    }

    public VacancyRelevanceRunResponse active(UUID resumeId) {
        RunContext context = runs.values().stream()
                .filter(run -> run.resumeId.equals(resumeId) && run.terminal == null)
                .findFirst().orElse(null);
        if (context == null) return null;
        VacancyRelevanceRunResponse response = find(context.id);
        return "queued".equals(response.status()) || "running".equals(response.status()) ? response : null;
    }

    public List<VacancyRelevanceNotificationRun> notificationRuns() {
        cleanupRuns();
        runs.values().stream().filter(run -> run.terminal == null).toList().forEach(run -> {
            try { find(run.id); } catch (ApplicationException ignored) { /* A later monitor tick retries transient failures. */ }
        });
        cleanupRuns();
        return runs.values().stream()
                .sorted(Comparator.comparing(run -> run.createdAt))
                .map(RunContext::notification)
                .toList();
    }

    public VacancyRelevanceRunResponse find(UUID id) {
        RunContext context = runs.get(id);
        if (context == null) throw runNotFound();
        synchronized (context) {
            if (runs.get(id) != context || expiredTerminal(context, clock.instant())) {
                runs.remove(id, context);
                throw runNotFound();
            }
            if (context.terminal != null) return context.terminal;
            GenerationAgentRelevanceClient.GenerationJob job;
            try {
                job = generationAgent.find(id);
            } catch (ApplicationException exception) {
                if (!"VACANCY_RELEVANCE_JOB_LOST".equals(exception.machineCode())) throw exception;
                return terminalFailure(id, context, exception.machineCode(), exception.safeMessage());
            }
            if (job == null || !id.equals(job.id()) || !("queued".equals(job.status()) || "running".equals(job.status())
                    || "done".equals(job.status()) || "failed".equals(job.status()))) {
                ApplicationException exception = invalidAgentResponse();
                return terminalFailure(id, context, exception.machineCode(), exception.safeMessage());
            }
            if ("queued".equals(job.status()) || "running".equals(job.status())) {
                context.status = job.status();
                return response(id, context.resumeId, job.status(), context.idsByHhId.size(), 0, null, null);
            }
            if ("failed".equals(job.status())) {
                String code = job.error() == null ? "GENERATION_FAILED" : job.error().code();
                String message = job.error() == null ? "Generation Agent не смог оценить вакансии." : job.error().message();
                return terminalFailure(id, context, code, message);
            }
            return complete(id, context, job.result());
        }
    }

    private synchronized void cleanupRuns() {
        Instant now = clock.instant();
        runs.values().removeIf(run -> expiredTerminal(run, now));
        runs.values().stream()
                .filter(run -> run.terminal != null)
                .sorted(Comparator.comparing((RunContext run) -> run.completedAt).reversed())
                .skip(TERMINAL_HISTORY_LIMIT)
                .forEach(run -> runs.remove(run.id, run));
    }

    private boolean expiredTerminal(RunContext context, Instant now) {
        return context.terminal != null && !now.isBefore(context.completedAt.plus(TERMINAL_RETENTION));
    }

    private ApplicationException runNotFound() {
        return new ApplicationException(ApplicationErrorCategory.NOT_FOUND, "VACANCY_RELEVANCE_RUN_NOT_FOUND",
                "Оценка не найдена или backend был перезапущен.");
    }

    private VacancyRelevanceRunResponse complete(UUID id, RunContext context, GenerationAgentRelevanceClient.GenerationResult result) {
        Map<UUID, VacancyRelevance> evaluations;
        try {
            evaluations = validatedEvaluations(context.idsByHhId, result);
        } catch (ApplicationException exception) {
            if (!"INVALID_GENERATION_AGENT_RESPONSE".equals(exception.machineCode())) throw exception;
            return terminalFailure(id, context, exception.machineCode(), exception.safeMessage());
        }
        persistence.save(evaluations);
        VacancyRelevanceRunResponse terminal = response(
                id, context.resumeId, "done", context.idsByHhId.size(), evaluations.size(), null, null);
        cacheTerminal(context, terminal);
        return terminal;
    }

    Map<UUID, VacancyRelevance> validatedEvaluations(
            Map<String, UUID> idsByHhId, GenerationAgentRelevanceClient.GenerationResult result) {
        if (result == null || result.items() == null || result.items().isEmpty()) throw invalidAgentResponse();
        Map<UUID, VacancyRelevance> evaluations = new LinkedHashMap<>();
        Instant analyzedAt = clock.instant();
        for (var item : result.items()) {
            UUID vacancyId = item == null ? null : idsByHhId.get(item.hhId());
            if (vacancyId == null || evaluations.containsKey(vacancyId) || item.reason() == null
                    || item.reason().isBlank() || item.reason().length() > 200) throw invalidAgentResponse();
            try {
                evaluations.put(vacancyId, new VacancyRelevance(
                        VacancyRelevanceCategory.valueOf(item.category()),
                        VacancyRelevanceConfidence.valueOf(item.confidence()),
                        item.reason().trim(), analyzedAt));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw invalidAgentResponse();
            }
        }
        return evaluations;
    }

    private VacancyRelevanceRunResponse terminalFailure(UUID id, RunContext context, String code, String message) {
        VacancyRelevanceRunResponse terminal = response(id, context.resumeId, "failed", context.idsByHhId.size(), 0, code, message);
        cacheTerminal(context, terminal);
        return terminal;
    }

    private void cacheTerminal(RunContext context, VacancyRelevanceRunResponse terminal) {
        synchronized (this) {
            context.status = terminal.status();
            context.completedAt = clock.instant();
            context.revision = context.completedAt.toString();
            context.terminal = terminal;
        }
    }

    GenerationAgentRelevanceClient.VacancyInput toInput(Vacancy vacancy) {
        String salary = vacancy.salaryText();
        if ((salary == null || salary.isBlank()) && vacancy.salary() != null) salary = vacancy.salary().toString();
        JsonNode enrichment = vacancy.enrichment();
        String plainText = vacancies.findText(vacancy.id()).map(VacancyText::plainText).orElse(null);
        return new GenerationAgentRelevanceClient.VacancyInput(
                vacancy.hhVacancyId(), vacancy.title(), value(vacancy.companyName()), value(salary),
                normalizedSalary(vacancy, enrichment), value(vacancy.location()), text(enrichment, "address"),
                strings(enrichment, "workFormats", vacancy.workFormat()), text(enrichment, "employmentForm"),
                strings(enrichment, "workScheduleByDays", null), value(vacancy.experience()),
                bool(enrichment, "internship"), bool(enrichment, "temporary"),
                strings(enrichment, "professionalRoles", null), value(plainText));
    }

    private GenerationAgentRelevanceClient.NormalizedSalaryInput normalizedSalary(Vacancy vacancy, JsonNode enrichment) {
        JsonNode compensation = object(enrichment, "compensation");
        Long from = vacancy.salary() == null ? number(compensation, "from") : vacancy.salary().from();
        Long to = vacancy.salary() == null ? number(compensation, "to") : vacancy.salary().to();
        String currency = vacancy.salary() == null ? nullableText(compensation, "currency") : vacancy.salary().currency().name();
        Boolean gross = vacancy.salary() == null ? bool(compensation, "gross") : Boolean.valueOf(vacancy.salary().gross());
        String mode = nullableText(compensation, "mode");
        String frequency = nullableText(compensation, "frequency");
        if (from == null && to == null && currency == null && gross == null && mode == null && frequency == null) return null;
        return new GenerationAgentRelevanceClient.NormalizedSalaryInput(from, to, currency, gross, mode, frequency);
    }

    private JsonNode object(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private String text(JsonNode source, String field) {
        return value(nullableText(source, field));
    }

    private String nullableText(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private Long number(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        return value != null && value.isIntegralNumber() ? value.longValue() : null;
    }

    private Boolean bool(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private List<String> strings(JsonNode source, String field, String fallback) {
        JsonNode value = source == null ? null : source.get(field);
        if (value != null && value.isArray()) {
            List<String> result = new ArrayList<>();
            value.forEach(item -> { if (item.isString() && !item.asString().isBlank()) result.add(item.asString()); });
            if (!result.isEmpty()) return List.copyOf(result);
        }
        return fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private VacancyRelevanceRunResponse response(UUID id, UUID resumeId, String status, int requested, int evaluated, String code, String message) {
        return new VacancyRelevanceRunResponse(id, resumeId, status, requested, evaluated, requested - evaluated, code, message);
    }

    private ApplicationException invalidAgentResponse() {
        return new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM, "INVALID_GENERATION_AGENT_RESPONSE",
                "Generation Agent вернул некорректный результат оценки.");
    }

    private ApplicationException validation(String code, String message) {
        return new ApplicationException(ApplicationErrorCategory.VALIDATION, code, message);
    }

    private static final class RunContext {
        private final UUID id;
        private final UUID accountId;
        private final UUID resumeId;
        private final String resumeName;
        private final Map<String, UUID> idsByHhId;
        private final boolean finalBatch;
        private final int totalCount;
        private final int evaluatedBefore;
        private final Instant createdAt;
        private volatile String status;
        private volatile String revision;
        private volatile Instant completedAt;
        private volatile VacancyRelevanceRunResponse terminal;

        private RunContext(UUID id, UUID accountId, UUID resumeId, String resumeName, Map<String, UUID> idsByHhId,
                           String status, boolean finalBatch, int totalCount, int evaluatedBefore, Instant createdAt) {
            this.id = id;
            this.accountId = accountId;
            this.resumeId = resumeId;
            this.resumeName = resumeName;
            this.idsByHhId = idsByHhId;
            this.status = status;
            this.finalBatch = finalBatch;
            this.totalCount = totalCount;
            this.evaluatedBefore = evaluatedBefore;
            this.createdAt = createdAt;
            this.revision = createdAt.toString();
        }

        private VacancyRelevanceNotificationRun notification() {
            int batchEvaluated = terminal == null ? 0 : terminal.evaluatedCount();
            int requested = finalBatch ? totalCount : idsByHhId.size();
            int evaluated = finalBatch ? evaluatedBefore + batchEvaluated : batchEvaluated;
            String error = terminal == null ? null : terminal.errorMessage();
            return new VacancyRelevanceNotificationRun(id, accountId, resumeId, resumeName, status, revision,
                    finalBatch, requested, evaluated, requested - evaluated, error);
        }
    }
}
