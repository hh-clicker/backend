package org.hhclicker.backend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.application.controller.ApplicationRunController;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.application.service.ApplicationRunGateway;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт модуля application. Контроллер поднимается вместе с настоящим
 * {@link GlobalExceptionHandler}, поэтому проверяются и коды статусов, и формат ProblemDetail.
 */
class ApplicationRunControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-04T09:00:00Z");

    private final ApplicationService applications = mock(ApplicationService.class);
    private final ApplicationRunGateway gateway = mock(ApplicationRunGateway.class);

    private final UUID runId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID vacancyId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ApplicationRunController(applications, gateway))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shortHistoryOfTheResumeCarriesCountersRevisionAndEstimate() throws Exception {
        when(applications.summaries(resumeId)).thenReturn(List.of(new RunSummary(
                runId, 7, resumeId, "Java Developer", RunStatus.PAUSED_ERROR, CREATED_AT, 3, 1,
                "revision-hash", 180L)));

        mockMvc.perform(get("/api/v1/application-runs").param("resumeId", resumeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(runId.toString()))
                .andExpect(jsonPath("$[0].sequence").value(7))
                .andExpect(jsonPath("$[0].status").value("PAUSED_ERROR"))
                .andExpect(jsonPath("$[0].total").value(3))
                .andExpect(jsonPath("$[0].attention").value(1))
                .andExpect(jsonPath("$[0].revision").value("revision-hash"))
                .andExpect(jsonPath("$[0].estimatedRemainingSeconds").value(180))
                .andExpect(jsonPath("$[0].applications").doesNotExist())
                .andExpect(jsonPath("$[0].coverLetter").doesNotExist())
                .andExpect(jsonPath("$[0].letter").doesNotExist());
    }

    @Test
    void shortHistoryWithoutResumeIdIsRejectedAsRequestValidation() throws Exception {
        mockMvc.perform(get("/api/v1/application-runs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void runDetailsExposeQueueOutcomeAndPreparedLetter() throws Exception {
        when(applications.find(runId)).thenReturn(run(RunStatus.PAUSED_ERROR));

        mockMvc.perform(get("/api/v1/application-runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("PAUSED_ERROR"))
                .andExpect(jsonPath("$.dailyLimit").value(20))
                .andExpect(jsonPath("$.pauseFromSeconds").value(30))
                .andExpect(jsonPath("$.pauseToSeconds").value(90))
                .andExpect(jsonPath("$.aiLetter.enabled").value(false))
                .andExpect(jsonPath("$.applications.length()").value(1))
                .andExpect(jsonPath("$.applications[0].id").value(itemId.toString()))
                .andExpect(jsonPath("$.applications[0].vacancyId").value(vacancyId.toString()))
                .andExpect(jsonPath("$.applications[0].status").value("ATTENTION_REQUIRED"))
                .andExpect(jsonPath("$.applications[0].outcome").value("QUESTIONNAIRE"))
                .andExpect(jsonPath("$.applications[0].attentionStatus").value("OPEN"))
                .andExpect(jsonPath("$.applications[0].hasQuestionnaire").value(true))
                .andExpect(jsonPath("$.applications[0].letter.source").value("STATIC"));
    }

    @Test
    void unknownRunIsReportedAsConflictProblemDetail() throws Exception {
        when(applications.find(runId)).thenThrow(new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "APPLICATION_RUN_CONFLICT", "Run not found."));

        mockMvc.perform(get("/api/v1/application-runs/{id}", runId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_RUN_CONFLICT"))
                .andExpect(jsonPath("$.category").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Run not found."));
    }

    @Test
    void malformedRunIdIsRejectedAsRequestValidation() throws Exception {
        mockMvc.perform(get("/api/v1/application-runs/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void startIsDelegatedToTheGatewayWithTheMappedCommand() throws Exception {
        when(gateway.startApplications(any())).thenReturn(run(RunStatus.RUNNING));

        mockMvc.perform(post("/api/v1/application-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(List.of(vacancyId), 20, 30, 90)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        ArgumentCaptor<Start> captor = ArgumentCaptor.forClass(Start.class);
        verify(gateway).startApplications(captor.capture());
        Start captured = captor.getValue();
        assertEquals(runId, captured.requestId());
        assertEquals(resumeId, captured.resumeId());
        assertEquals(List.of(vacancyId), captured.vacancyIds());
        assertEquals(20, captured.dailyLimit());
        assertEquals(30, captured.pauseFromSeconds());
        assertEquals(90, captured.pauseToSeconds());
        assertEquals("Cover letter", captured.coverLetter());
        assertEquals(AiLetterSettings.defaults(), captured.aiLetter(),
                "отсутствующие настройки генерации должны превращаться в значения по умолчанию");
    }

    @Test
    void startWithEmptyQueueIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/application-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(List.of(), 20, 30, 90)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(gateway, never()).startApplications(any());
    }

    @Test
    void startWithTooShortPauseIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/application-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(List.of(vacancyId), 20, 5, 90)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(gateway, never()).startApplications(any());
    }

    @Test
    void startAboveTheAllowedDailyLimitIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/application-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(List.of(vacancyId), 501, 30, 90)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void startWithoutCoverLetterIsRejected() throws Exception {
        String body = """
                {"requestId":"%s","resumeId":"%s","vacancyIds":["%s"],"dailyLimit":20,
                 "pauseFromSeconds":30,"pauseToSeconds":90,"skipQuestionnaires":true}
                """.formatted(runId, resumeId, vacancyId);

        mockMvc.perform(post("/api/v1/application-runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void startWithoutRequestIdIsRejected() throws Exception {
        String body = """
                {"resumeId":"%s","vacancyIds":["%s"],"dailyLimit":20,"pauseFromSeconds":30,
                 "pauseToSeconds":90,"coverLetter":"Cover letter","skipQuestionnaires":true}
                """.formatted(resumeId, vacancyId);

        mockMvc.perform(post("/api/v1/application-runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void startRejectedByTheGatewayIsReportedAsConflictProblemDetail() throws Exception {
        when(gateway.startApplications(any())).thenThrow(new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "APPLICATION_RUN_CONFLICT", "Finish the previous run first."));

        mockMvc.perform(post("/api/v1/application-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(List.of(vacancyId), 20, 30, 90)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_RUN_CONFLICT"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void malformedJsonBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/application-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void statusChangeIsDelegatedToTheGateway() throws Exception {
        when(gateway.changeApplicationStatus(runId, RunStatus.PAUSED_USER)).thenReturn(run(RunStatus.PAUSED_USER));

        mockMvc.perform(put("/api/v1/application-runs/{id}/status", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED_USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED_USER"));

        verify(gateway).changeApplicationStatus(runId, RunStatus.PAUSED_USER);
    }

    @Test
    void unknownStatusValueIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/application-runs/{id}/status", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FLYING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(gateway, never()).changeApplicationStatus(any(), any());
    }

    @Test
    void missingStatusIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/application-runs/{id}/status", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(gateway, never()).changeApplicationStatus(any(), any());
    }

    @Test
    void statusChangeRejectedByTheGatewayIsReportedAsConflictProblemDetail() throws Exception {
        when(gateway.changeApplicationStatus(runId, RunStatus.RUNNING)).thenThrow(new ApplicationException(
                ApplicationErrorCategory.CONFLICT, "APPLICATION_RUN_CONFLICT", "Daily limit reached."));

        mockMvc.perform(put("/api/v1/application-runs/{id}/status", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_RUN_CONFLICT"));
    }

    private String startBody(List<UUID> vacancyIds, int dailyLimit, int pauseFrom, int pauseTo) {
        String ids = vacancyIds.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return """
                {"requestId":"%s","resumeId":"%s","vacancyIds":[%s],"dailyLimit":%d,
                 "pauseFromSeconds":%d,"pauseToSeconds":%d,"coverLetter":"Cover letter","skipQuestionnaires":true}
                """.formatted(runId, resumeId, ids, dailyLimit, pauseFrom, pauseTo);
    }

    private Run run(RunStatus status) {
        Item item = new Item(itemId, runId, vacancyId, "12345678", "Java Developer", "Example",
                "ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, CREATED_AT, null, true, "OPEN",
                new PreparedLetter("Cover letter", "STATIC", null, null));
        return new Run(runId, 7, UUID.randomUUID(), resumeId, UUID.randomUUID(), "abcdef0123456789abcd",
                "Java Developer", status, CREATED_AT, null, 20, 20, 30, 90, "Cover letter", true,
                List.of(item), AiLetterSettings.defaults());
    }
}
