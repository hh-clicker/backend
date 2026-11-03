package org.hhclicker.backend.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.shared.config.ExternalHttpClient;

import java.util.List;
import java.util.UUID;

@Component
public class MarketGenerationClient {
    private final RestClient client;

    @Autowired
    public MarketGenerationClient(@Value("${hh-clicker.generation-agent.base-url:http://localhost:17374}") String baseUrl) {
        this(ExternalHttpClient.generationAgent(), baseUrl);
    }

    MarketGenerationClient(RestClient.Builder builder, String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    public record VacancyInput(UUID id, String title, String text) { }
    public record RequirementInput(UUID id, String name) { }
    public record ExtractionRequest(List<VacancyInput> vacancies) { }
    public record CoverageRequest(String resumeText, List<RequirementInput> requirements) { }
    public record SubmittedJob(UUID id, String status) { }
    public record ExtractedFact(String name, String kind, String evidence) { }
    public record ExtractedVacancy(UUID id, List<ExtractedFact> facts) { }
    public record AgentDiagnostic(UUID id, String reason, String name, String evidence) { }
    public record ExtractionResult(List<ExtractedVacancy> items, List<AgentDiagnostic> diagnostics) {
        public ExtractionResult(List<ExtractedVacancy> items) { this(items, List.of()); }
    }
    public record CoverageItem(UUID id, String status, String evidence) { }
    public record CoverageResult(List<CoverageItem> items, List<AgentDiagnostic> diagnostics) {
        public CoverageResult(List<CoverageItem> items) { this(items, List.of()); }
    }
    public record AgentError(String code, String message, String reason, Boolean retried) {
        public AgentError(String code, String message) { this(code, message, null, null); }
    }
    public record ExtractionJob(UUID id, String status, ExtractionResult result, AgentError error) { }
    public record CoverageJob(UUID id, String status, CoverageResult result, AgentError error) { }

    public SubmittedJob extract(ExtractionRequest request, String key) {
        try {
            return client.post().uri("/market-extraction").header("Idempotency-Key", key)
                    .body(request).retrieve().body(SubmittedJob.class);
        } catch (RestClientResponseException cause) { throw requestFailure(cause); }
        catch (RestClientException cause) { throw unavailable(cause); }
    }

    public ExtractionJob extraction(UUID id) {
        try { return client.get().uri("/market-extraction/{id}", id).retrieve().body(ExtractionJob.class); }
        catch (RestClientResponseException cause) { throw responseFailure(cause); }
        catch (RestClientException cause) { throw unavailable(cause); }
    }

    public SubmittedJob cover(CoverageRequest request, String key) {
        try {
            return client.post().uri("/market-coverage").header("Idempotency-Key", key)
                    .body(request).retrieve().body(SubmittedJob.class);
        } catch (RestClientResponseException cause) { throw requestFailure(cause); }
        catch (RestClientException cause) { throw unavailable(cause); }
    }

    public CoverageJob coverage(UUID id) {
        try { return client.get().uri("/market-coverage/{id}", id).retrieve().body(CoverageJob.class); }
        catch (RestClientResponseException cause) { throw responseFailure(cause); }
        catch (RestClientException cause) { throw unavailable(cause); }
    }

    private ApplicationException responseFailure(RestClientResponseException cause) {
        if (cause.getStatusCode().value() == 404) return new ApplicationException(ApplicationErrorCategory.INTERNAL,
                "MARKET_GENERATION_JOB_LOST", "Задача Generation Agent больше не найдена.", cause);
        return requestFailure(cause);
    }

    private ApplicationException requestFailure(RestClientResponseException cause) {
        int status = cause.getStatusCode().value();
        if (status == 409) return new ApplicationException(ApplicationErrorCategory.INTERNAL,
                "MARKET_AGENT_KEY_CONFLICT", "Ключ задания уже связан с другим входом или настройками. Продолжите анализ для новой попытки.", cause);
        if (cause.getStatusCode().is4xxClientError() && status != 408 && status != 429)
            return new ApplicationException(ApplicationErrorCategory.INTERNAL, "MARKET_AGENT_REQUEST_REJECTED",
                    "Generation Agent отклонил запрос анализа (HTTP " + status + "). Проверьте совместимость компонентов.", cause);
        return unavailable(cause);
    }

    private ApplicationException unavailable(RestClientException cause) {
        return new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM, "GENERATION_AGENT_UNAVAILABLE",
                "Не удалось связаться с Generation Agent.", cause);
    }
}
