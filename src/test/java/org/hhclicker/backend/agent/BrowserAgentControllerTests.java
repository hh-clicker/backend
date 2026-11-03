package org.hhclicker.backend.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.agent.controller.BrowserAgentController;
import org.hhclicker.backend.agent.exception.InvalidBrowserAgentDataException;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.agent.model.BrowserAgentSnapshot;
import org.hhclicker.backend.agent.service.BrowserAgentStatusService;
import org.hhclicker.backend.agent.service.GenerationStatusService;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт heartbeat и состояния Browser Agent.
 */
class BrowserAgentControllerTests {

    private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");

    private final BrowserAgentStatusService statusService = mock(BrowserAgentStatusService.class);
    private final GenerationStatusService generationStatus = mock(GenerationStatusService.class);

    private final UUID profileId = UUID.randomUUID();
    private final UUID checkId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BrowserAgentController(statusService, generationStatus))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private BrowserAgentSnapshot snapshot() {
        return new BrowserAgentSnapshot("local-browser-agent", "1.2.3", BrowserAgentRuntimeStatus.READY,
                NOW, 15, true, profileId);
    }

    private String heartbeatBody(String agentId, String version, String status) {
        return """
                {"agentId":"%s","version":"%s","status":"%s","browserOpen":true,"activeProfileId":"%s"}
                """.formatted(agentId, version, status, profileId);
    }

    @Test
    void heartbeatIsStoredAndAnsweredWithTheCurrentSnapshot() throws Exception {
        when(statusService.heartbeat(anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(snapshot());
        when(generationStatus.requestId()).thenReturn(checkId);

        mockMvc.perform(post("/api/v1/browser-agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody("local-browser-agent", "1.2.3", "READY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("local-browser-agent"))
                .andExpect(jsonPath("$.version").value("1.2.3"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.offlineAfterSeconds").value(15))
                .andExpect(jsonPath("$.browserOpen").value(true))
                .andExpect(jsonPath("$.activeProfileId").value(profileId.toString()))
                .andExpect(jsonPath("$.lastHeartbeatAt").exists())
                .andExpect(jsonPath("$.generationCheckId").value(checkId.toString()));

        verify(statusService).heartbeat("local-browser-agent", "1.2.3",
                BrowserAgentRuntimeStatus.READY, true, profileId);
    }

    @Test
    void generationReportIsForwardedTogetherWithTheAgentIdentifier() throws Exception {
        when(statusService.heartbeat(anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(snapshot());
        UUID reportCheckId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String body = """
                {"agentId":"local-browser-agent","version":"1.2.3","status":"READY","browserOpen":false,
                 "generation":{"checkId":"%s","requestId":"%s","state":"READY","ageMs":250,
                   "model":"gpt-5.6","reasoningEffort":"low","cliVersion":"0.153.0","queued":0,"running":1}}
                """.formatted(reportCheckId, requestId);

        mockMvc.perform(post("/api/v1/browser-agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<GenerationStatusService.Report> captor =
                ArgumentCaptor.forClass(GenerationStatusService.Report.class);
        verify(generationStatus).accept(org.mockito.ArgumentMatchers.eq("local-browser-agent"), captor.capture());
        GenerationStatusService.Report report = captor.getValue();
        assertEquals(reportCheckId, report.checkId());
        assertEquals(requestId, report.requestId());
        assertEquals(GenerationStatusService.State.READY, report.state());
        assertEquals(250, report.ageMs());
        assertEquals("gpt-5.6", report.model());
        assertEquals(1, report.running());
    }

    @Test
    void heartbeatWithoutAGenerationReportStillPassesNullThrough() throws Exception {
        when(statusService.heartbeat(anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(snapshot());

        mockMvc.perform(post("/api/v1/browser-agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody("local-browser-agent", "1.2.3", "READY")))
                .andExpect(status().isOk());

        verify(generationStatus).accept("local-browser-agent", null);
    }

    @Test
    void heartbeatRequiresAnIdentifierAVersionAndAStatus() throws Exception {
        expectValidationFailure(heartbeatBody("   ", "1.2.3", "READY"));
        expectValidationFailure(heartbeatBody("local-browser-agent", "  ", "READY"));
        expectValidationFailure("""
                {"agentId":"local-browser-agent","version":"1.2.3","browserOpen":true}
                """);

        verify(statusService, never()).heartbeat(anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void overlongIdentifierOrVersionIsRejected() throws Exception {
        expectValidationFailure(heartbeatBody("a".repeat(101), "1.2.3", "READY"));
        expectValidationFailure(heartbeatBody("local-browser-agent", "1".repeat(51), "READY"));
    }

    @Test
    void agentIsNotAllowedToAnnounceItselfOfflineThroughHttp() throws Exception {
        expectValidationFailure(heartbeatBody("local-browser-agent", "1.2.3", "OFFLINE"));

        verify(statusService, never()).heartbeat(anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void unknownRuntimeStatusIsRejected() throws Exception {
        expectValidationFailure(heartbeatBody("local-browser-agent", "1.2.3", "SLEEPING"));
    }

    @Test
    void generationReportWithAnImpossibleAgeIsRejected() throws Exception {
        expectValidationFailure("""
                {"agentId":"local-browser-agent","version":"1.2.3","status":"READY","browserOpen":false,
                 "generation":{"checkId":"%s","requestId":"%s","state":"READY","ageMs":60002}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()));

        verify(generationStatus, never()).accept(anyString(), any());
    }

    @Test
    void domainRejectionOfAHeartbeatIsReportedAsAValidationProblem() throws Exception {
        when(statusService.heartbeat(anyString(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new InvalidBrowserAgentDataException("Browser Agent cannot report OFFLINE status"));

        mockMvc.perform(post("/api/v1/browser-agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(heartbeatBody("local-browser-agent", "1.2.3", "READY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BROWSER_AGENT_DATA"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void statusEndpointReturnsTheSnapshotWithoutAGenerationCheckId() throws Exception {
        when(statusService.getStatus()).thenReturn(snapshot());

        mockMvc.perform(get("/api/v1/browser-agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("local-browser-agent"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.generationCheckId").value(nullValue()));

        verify(generationStatus, never()).requestId();
    }

    @Test
    void statusOfAnAgentThatNeverConnectedIsReportedAsOffline() throws Exception {
        when(statusService.getStatus()).thenReturn(BrowserAgentSnapshot.offline(15));

        mockMvc.perform(get("/api/v1/browser-agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.agentId").value(nullValue()))
                .andExpect(jsonPath("$.browserOpen").value(false));
    }

    private void expectValidationFailure(String body) throws Exception {
        RequestBuilder request = post("/api/v1/browser-agent/heartbeat")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
}
