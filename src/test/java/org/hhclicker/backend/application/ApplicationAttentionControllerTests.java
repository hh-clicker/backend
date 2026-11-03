package org.hhclicker.backend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.application.controller.ApplicationAttentionController;
import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт задач внимания: список по резюме и ручное закрытие задачи.
 */
class ApplicationAttentionControllerTests {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-04T09:00:00Z");

    private final ApplicationService applications = mock(ApplicationService.class);

    private final UUID resumeId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID vacancyId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ApplicationAttentionController(applications))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void attentionTasksOfTheResumeAreReturned() throws Exception {
        when(applications.attention(resumeId)).thenReturn(List.of(attention("OPEN", true)));

        mockMvc.perform(get("/api/v1/attention-tasks").param("resumeId", resumeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(itemId.toString()))
                .andExpect(jsonPath("$[0].kind").value("QUESTIONNAIRE"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].runId").value(runId.toString()))
                .andExpect(jsonPath("$[0].vacancyId").value(vacancyId.toString()))
                .andExpect(jsonPath("$[0].employerId").value("3529"))
                .andExpect(jsonPath("$[0].outcome").value("QUESTIONNAIRE"))
                .andExpect(jsonPath("$[0].canContinueRun").value(true))
                .andExpect(jsonPath("$[0].canSkipVacancy").value(true));
    }

    @Test
    void acknowledgementIsDelegatedToTheService() throws Exception {
        when(applications.attention(itemId, "RESOLVED")).thenReturn(attention("RESOLVED", false));

        mockMvc.perform(put("/api/v1/attention-tasks/{id}/status", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.canContinueRun").value(false));

        verify(applications).attention(itemId, "RESOLVED");
    }

    @Test
    void acknowledgementWithoutStatusIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/attention-tasks/{id}/status", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(applications, never()).attention(any(), anyString());
    }

    @Test
    void unsupportedAcknowledgementStatusIsReportedAsConflictProblemDetail() throws Exception {
        when(applications.attention(itemId, "DONE")).thenThrow(new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "APPLICATION_RUN_CONFLICT", "Unsupported task state."));

        mockMvc.perform(put("/api/v1/attention-tasks/{id}/status", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_RUN_CONFLICT"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void malformedTaskIdIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/attention-tasks/{id}/status", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(applications, never()).attention(any(), anyString());
    }

    private Attention attention(String status, boolean canContinueRun) {
        return new Attention(itemId, "QUESTIONNAIRE", status, "Fill in the employer form", "Manual step required",
                runId, 7, itemId, vacancyId, "12345678", "Java Developer", "3529", "Example", "Java Developer",
                OCCURRED_AT, Outcome.QUESTIONNAIRE, canContinueRun, true);
    }
}
