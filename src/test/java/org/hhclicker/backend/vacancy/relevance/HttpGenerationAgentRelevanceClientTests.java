package org.hhclicker.backend.vacancy.relevance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpGenerationAgentRelevanceClientTests {

    private MockRestServiceServer server;
    private HttpGenerationAgentRelevanceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpGenerationAgentRelevanceClient(builder, "http://generation.test");
    }

    @Test
    void submitUsesDedicatedEndpointAndJsonContract() {
        UUID id = UUID.randomUUID();
        server.expect(once(), requestTo("http://generation.test/vacancy-relevance"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"resumeText":"PHP","vacancies":[{"hhId":"101","title":"PHP developer","company":"Web","salary":"","normalizedSalary":null,"location":"","address":"","workFormats":[],"employmentForm":"","workScheduleByDays":[],"experience":"","internship":null,"temporary":null,"professionalRoles":[],"plainText":""}]}
                        """))
                .andRespond(withSuccess("{\"id\":\"%s\",\"status\":\"queued\"}".formatted(id),
                        org.springframework.http.MediaType.APPLICATION_JSON));

        var response = client.submit(new GenerationAgentRelevanceClient.GenerationRequest("PHP", List.of(
                new GenerationAgentRelevanceClient.VacancyInput("101", "PHP developer", "Web", "", null,
                        "", "", List.of(), "", List.of(), "", null, null, List.of()))));

        assertEquals(id, response.id());
        assertEquals("queued", response.status());
        server.verify();
    }

    @Test
    void parallelBatchSendsItsOwnIdempotencyKey() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://generation.test/vacancy-relevance"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key", "relevance-parent-3"))
                .andRespond(withSuccess("{\"id\":\"%s\",\"status\":\"queued\"}".formatted(id),
                        org.springframework.http.MediaType.APPLICATION_JSON));
        assertEquals(id, client.submit(new GenerationAgentRelevanceClient.GenerationRequest("Java", List.of()),
                "relevance-parent-3").id());
        server.verify();
    }

    @Test
    void pollReadsPartialStructuredResult() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://generation.test/vacancy-relevance/" + id))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","status":"done","result":{"items":[{"hhId":"101","category":"TARGET","confidence":"high","reason":"PHP"}]},"error":null}
                        """.formatted(id), org.springframework.http.MediaType.APPLICATION_JSON));

        var response = client.find(id);

        assertEquals("done", response.status());
        assertEquals("101", response.result().items().getFirst().hhId());
        server.verify();
    }

    @Test
    void notFoundPollIsReportedAsLostJob() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://generation.test/vacancy-relevance/" + id))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND).body("private diagnostics"));

        var exception = assertThrows(ApplicationException.class, () -> client.find(id));

        assertEquals("VACANCY_RELEVANCE_JOB_LOST", exception.machineCode());
        assertEquals("Задание оценки вакансий больше не найдено. Запустите оценку повторно.",
                exception.safeMessage());
        server.verify();
    }

    @Test
    void serverErrorDuringPollRemainsTemporaryUnavailability() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://generation.test/vacancy-relevance/" + id))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError().body("private diagnostics"));

        var exception = assertThrows(ApplicationException.class, () -> client.find(id));

        assertEquals("GENERATION_AGENT_UNAVAILABLE", exception.machineCode());
        assertEquals("Не удалось связаться с Generation Agent.", exception.safeMessage());
        server.verify();
    }

    @Test
    void transportAndHttpFailuresBecomeSafeExternalSystemErrors() {
        server.expect(requestTo("http://generation.test/vacancy-relevance"))
                .andRespond(withServerError().body("private diagnostics"));

        var exception = assertThrows(ApplicationException.class, () -> client.submit(
                new GenerationAgentRelevanceClient.GenerationRequest("PHP", List.of())));

        assertEquals("GENERATION_AGENT_UNAVAILABLE", exception.machineCode());
        assertEquals("Не удалось связаться с Generation Agent.", exception.safeMessage());
    }
}
