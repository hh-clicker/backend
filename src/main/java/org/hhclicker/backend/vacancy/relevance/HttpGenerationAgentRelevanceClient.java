package org.hhclicker.backend.vacancy.relevance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.shared.config.ExternalHttpClient;

import java.util.UUID;

@Component
public class HttpGenerationAgentRelevanceClient implements GenerationAgentRelevanceClient {

    private final RestClient client;

    @Autowired
    public HttpGenerationAgentRelevanceClient(
            @Value("${hh-clicker.generation-agent.base-url:http://localhost:17374}") String baseUrl
    ) {
        this(ExternalHttpClient.generationAgent(), baseUrl);
    }

    HttpGenerationAgentRelevanceClient(RestClient.Builder builder, String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public SubmittedJob submit(GenerationRequest request) {
        return submit(request, null);
    }

    @Override
    public SubmittedJob submit(GenerationRequest request, String idempotencyKey) {
        try {
            var call = client.post().uri("/vacancy-relevance");
            if (idempotencyKey != null) call.header("Idempotency-Key", idempotencyKey);
            return call.body(request)
                    .retrieve()
                    .body(SubmittedJob.class);
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public GenerationJob find(UUID id) {
        try {
            return client.get().uri("/vacancy-relevance/{id}", id)
                    .retrieve()
                    .body(GenerationJob.class);
        } catch (RestClientResponseException exception) {
            throw pollFailure(exception);
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    private ApplicationException pollFailure(RestClientResponseException cause) {
        if (cause.getStatusCode().value() == 404) {
            return new ApplicationException(
                    ApplicationErrorCategory.EXTERNAL_SYSTEM,
                    "VACANCY_RELEVANCE_JOB_LOST",
                    "Задание оценки вакансий больше не найдено. Запустите оценку повторно.",
                    cause
            );
        }
        return unavailable(cause);
    }

    private ApplicationException unavailable(RestClientException cause) {
        return new ApplicationException(
                ApplicationErrorCategory.EXTERNAL_SYSTEM,
                "GENERATION_AGENT_UNAVAILABLE",
                "Не удалось связаться с Generation Agent.",
                cause
        );
    }
}
