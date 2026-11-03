package org.hhclicker.backend.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MarketGenerationClientTests {
    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 413, 422, 408, 429, 503})
    void submissionSeparatesPermanentRejectionsFromRetryableHttpFailures(int status) {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new MarketGenerationClient(builder, "http://generation.test");
        server.expect(anything()).andRespond(withStatus(org.springframework.http.HttpStatus.valueOf(status))
                .body("private-response-with-token"));
        var failure = assertThrows(ApplicationException.class, () -> client.extract(
                new MarketGenerationClient.ExtractionRequest(java.util.List.of()), "same-key"));
        String expected = status == 409 ? "MARKET_AGENT_KEY_CONFLICT"
                : status == 408 || status == 429 || status >= 500 ? "GENERATION_AGENT_UNAVAILABLE"
                : "MARKET_AGENT_REQUEST_REJECTED";
        assertEquals(expected, failure.machineCode());
        assertFalse(failure.safeMessage().contains("private-response"));
        server.verify();
    }

    @Test
    void pollingDistinguishesLostJobsFromTemporaryFailures() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new MarketGenerationClient(builder, "http://generation.test");
        server.expect(anything()).andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
        server.expect(anything()).andRespond(withServerError());
        server.expect(anything()).andRespond(withException(new IOException("Synthetic timeout")));

        assertEquals("MARKET_GENERATION_JOB_LOST", assertThrows(ApplicationException.class,
                () -> client.extraction(UUID.randomUUID())).machineCode());
        assertEquals(ApplicationErrorCategory.EXTERNAL_SYSTEM, assertThrows(ApplicationException.class,
                () -> client.extraction(UUID.randomUUID())).category());
        assertEquals("GENERATION_AGENT_UNAVAILABLE", assertThrows(ApplicationException.class,
                () -> client.extraction(UUID.randomUUID())).machineCode());
        server.verify();
    }
}
