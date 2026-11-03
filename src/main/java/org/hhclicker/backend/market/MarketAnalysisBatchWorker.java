package org.hhclicker.backend.market;

import org.hhclicker.backend.market.MarketAnalysisBatchRepository.Batch;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Processes one durable batch; persistence is outside the agent-error boundary. */
final class MarketAnalysisBatchWorker {
    @FunctionalInterface
    interface Pause { void await() throws InterruptedException; }

    private static final Map<String, String> AGENT_ERRORS = Map.ofEntries(
            Map.entry("RATE_LIMIT", "Лимит Codex исчерпан или запросы ограничены. Повторите позже."),
            Map.entry("AUTH_REQUIRED", "Требуется вход в Codex."),
            Map.entry("AUTH_CHECK_FAILED", "Не удалось проверить авторизацию Codex."),
            Map.entry("TIMEOUT", "Codex не завершил обработку за отведённое время."),
            Map.entry("NETWORK_ERROR", "Не удалось получить ответ Codex из-за сетевой ошибки."),
            Map.entry("MODEL_UNAVAILABLE", "Выбранная модель недоступна."),
            Map.entry("CLI_UNAVAILABLE", "Codex CLI недоступен."),
            Map.entry("CLI_CONFIG_ERROR", "Проверьте конфигурацию Codex CLI."),
            Map.entry("RESOURCE_EXHAUSTED", "Недостаточно ресурсов для запуска Codex CLI."),
            Map.entry("SCHEMA_REJECTED", "Codex отклонил формат ответа. Проверьте совместимость компонентов."),
            Map.entry("INVALID_OUTPUT", "Codex вернул некорректный результат анализа."),
            Map.entry("OUTPUT_TOO_LARGE", "Ответ Codex превысил допустимый размер."),
            Map.entry("INTERRUPTED", "Обработка задания Codex была прервана."),
            Map.entry("CODEX_FAILED", "Codex не смог обработать задание."));

    private record Failure(String code, String message, boolean retryableOnResume) { }

    private final MarketAnalysisBatchService state;
    private final MarketGenerationClient agent;
    private final LongSupplier nanoTime;
    private final Pause pause;
    private final long deadline;

    MarketAnalysisBatchWorker(MarketAnalysisBatchService state, MarketGenerationClient agent) {
        this(state, agent, System::nanoTime, () -> Thread.sleep(1000), Duration.ofMinutes(20));
    }

    MarketAnalysisBatchWorker(MarketAnalysisBatchService state, MarketGenerationClient agent,
                              LongSupplier nanoTime, Pause pause, Duration deadline) {
        this.state = state;
        this.agent = agent;
        this.nanoTime = nanoTime;
        this.pause = pause;
        this.deadline = deadline.toNanos();
    }

    void execute(Batch initial, BooleanSupplier stopping) throws InterruptedException {
        Batch batch = initial;
        boolean retryAllowed = initial.errorCode() != null;
        long lastContact = nanoTime.getAsLong();
        Long executionStarted = null;
        var sources = state.load(batch.runId()).sources().subList(batch.start(), batch.start() + batch.size());
        var request = new MarketGenerationClient.ExtractionRequest(sources.stream().map(source ->
                new MarketGenerationClient.VacancyInput(source.id(), source.title(),
                        source.text().substring(0, Math.min(30_000, source.text().length())))).toList());

        while (!stopping.getAsBoolean() && state.accepts(batch.runId())) {
            UUID submittedId = null;
            MarketGenerationClient.ExtractionJob job = null;
            MarketExtractionResults.Accepted accepted = null;
            Failure failure = null;
            try {
                if (batch.jobId() == null) submittedId = submit(request, batch.key());
                else {
                    job = poll(batch.jobId());
                    if ("done".equals(job.status())) accepted = MarketExtractionResults.validate(sources, job);
                    if ("failed".equals(job.status())) failure = agentFailure(job.error());
                }
                lastContact = nanoTime.getAsLong();
            } catch (ApplicationException error) {
                if (error.category() != ApplicationErrorCategory.EXTERNAL_SYSTEM) {
                    failure = new Failure(error.machineCode(), error.safeMessage(),
                            Set.of("MARKET_GENERATION_JOB_LOST", "MARKET_AGENT_KEY_CONFLICT").contains(error.machineCode())
                                    || "MARKET_AGENT_INVALID_RESPONSE".equals(error.machineCode()) && batch.jobId() != null);
                }
            }

            if (failure != null) {
                if (retryAllowed && failure.retryableOnResume()) {
                    batch = retry(batch, failure);
                    if (batch == null) return;
                    retryAllowed = false;
                    executionStarted = null;
                    lastContact = nanoTime.getAsLong();
                    continue;
                }
                state.fail(batch, failure.code(), failure.message());
                return;
            }
            // Database exceptions propagate to the coordinator and retain the same durable checkpoint.
            if (submittedId != null) {
                batch = state.submitted(batch, submittedId);
                if (batch == null) return;
            }
            if (accepted != null) {
                state.complete(batch, accepted);
                return;
            }
            long now = nanoTime.getAsLong();
            if (job != null && "running".equals(job.status()) && executionStarted == null) executionStarted = now;
            if (now - lastContact >= deadline || executionStarted != null && now - executionStarted >= deadline) {
                state.fail(batch, "MARKET_AGENT_TIMEOUT", "Превышено время ожидания пачки. Можно продолжить анализ.");
                return;
            }
            pause.await();
        }
    }

    private UUID submit(MarketGenerationClient.ExtractionRequest request, String key) {
        var submitted = agent.extract(request, key);
        if (submitted == null || submitted.id() == null || submitted.status() == null
                || !Set.of("queued", "running", "done", "failed").contains(submitted.status())) throw invalidResponse();
        return submitted.id();
    }

    private MarketGenerationClient.ExtractionJob poll(UUID id) {
        var job = agent.extraction(id);
        if (job == null || !id.equals(job.id()) || job.status() == null
                || !Set.of("queued", "running", "done", "failed").contains(job.status())) throw invalidResponse();
        return job;
    }

    private Failure agentFailure(MarketGenerationClient.AgentError error) {
        String code = error == null ? null : error.code();
        String message = code == null ? null : AGENT_ERRORS.get(code);
        return message == null
                ? new Failure("MARKET_AGENT_FAILED", "Generation Agent не смог разобрать пачку вакансий. Можно продолжить анализ.", true)
                : new Failure(code, message, true);
    }

    private Batch retry(Batch previous, Failure failure) {
        Batch next = state.retry(previous);
        // Stop or a peer failure may forbid a retry; settle the accepted job instead of stranding it.
        if (next == null) state.fail(previous, failure.code(), failure.message());
        return next;
    }

    private ApplicationException invalidResponse() {
        return new ApplicationException(ApplicationErrorCategory.INTERNAL, "MARKET_AGENT_INVALID_RESPONSE",
                "Generation Agent вернул некорректный ответ анализа.");
    }
}
