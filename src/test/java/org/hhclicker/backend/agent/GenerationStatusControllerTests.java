package org.hhclicker.backend.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.agent.controller.GenerationStatusController;
import org.hhclicker.backend.agent.service.GenerationStatusService;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-контракт состояния generation-agent.
 */
class GenerationStatusControllerTests {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-04T09:00:00Z");

    private final GenerationStatusService service = mock(GenerationStatusService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GenerationStatusController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private GenerationStatusService.View ready() {
        return new GenerationStatusService.View(GenerationStatusService.State.READY, CHECKED_AT, false,
                "gpt-5.6-luna", "low", "0.153.0", 2, 1);
    }

    @Test
    void currentStateIsReturnedAndNeverCached() throws Exception {
        when(service.view()).thenReturn(ready());

        mockMvc.perform(get("/api/v1/generation-agent/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.refreshing").value(false))
                .andExpect(jsonPath("$.model").value("gpt-5.6-luna"))
                .andExpect(jsonPath("$.reasoningEffort").value("low"))
                .andExpect(jsonPath("$.cliVersion").value("0.153.0"))
                .andExpect(jsonPath("$.queued").value(2))
                .andExpect(jsonPath("$.running").value(1));

        verify(service).view();
        verify(service, never()).refresh();
    }

    @Test
    void unknownStateIsExposedWithEmptyDetails() throws Exception {
        when(service.view()).thenReturn(new GenerationStatusService.View(
                GenerationStatusService.State.UNKNOWN, null, true, null, null, null, null, null));

        mockMvc.perform(get("/api/v1/generation-agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.checkedAt").value(nullValue()))
                .andExpect(jsonPath("$.refreshing").value(true))
                .andExpect(jsonPath("$.model").value(nullValue()))
                .andExpect(jsonPath("$.queued").value(nullValue()));
    }

    @Test
    void offlineAgentIsExposedAsSuchWithoutAnError() throws Exception {
        when(service.view()).thenReturn(new GenerationStatusService.View(
                GenerationStatusService.State.AGENT_OFFLINE, null, false, null, null, null, null, null));

        mockMvc.perform(get("/api/v1/generation-agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AGENT_OFFLINE"));
    }

    @Test
    void refreshRequestsANewProbeAndAnswersWithTheUpdatedView() throws Exception {
        when(service.refresh()).thenReturn(new GenerationStatusService.View(
                GenerationStatusService.State.READY, CHECKED_AT, true, "gpt-5.6-luna", "low", "0.153.0", 0, 0));

        mockMvc.perform(post("/api/v1/generation-agent/status/refresh"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.refreshing").value(true));

        verify(service).refresh();
        verify(service, never()).view();
    }
}
