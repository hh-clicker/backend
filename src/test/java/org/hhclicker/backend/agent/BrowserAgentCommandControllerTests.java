package org.hhclicker.backend.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.agent.controller.BrowserAgentCommandController;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandConflictException;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandNotFoundException;
import org.hhclicker.backend.agent.exception.InvalidBrowserAgentDataException;
import org.hhclicker.backend.agent.model.*;
import org.hhclicker.backend.agent.service.BrowserAgentCommandService;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт очереди команд Browser Agent.
 */
class BrowserAgentCommandControllerTests {

    private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    private static final String AGENT_ID = "local-browser-agent";

    private final BrowserAgentCommandService commandService = mock(BrowserAgentCommandService.class);

    private final UUID commandId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BrowserAgentCommandController(commandService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private BrowserAgentCommand command(BrowserAgentCommandStatus status) {
        return new BrowserAgentCommand(commandId, BrowserAgentCommandType.OPEN_BROWSER,
                new BrowserAgentCommandPayload(profileId, null, null, null), status,
                status == BrowserAgentCommandStatus.QUEUED ? null : AGENT_ID,
                NOW, status == BrowserAgentCommandStatus.QUEUED ? null : NOW, null, null, null, null);
    }

    @Test
    void claimedCommandIsReturnedWithItsPayload() throws Exception {
        when(commandService.claimNext(AGENT_ID)).thenReturn(Optional.of(command(BrowserAgentCommandStatus.IN_PROGRESS)));

        mockMvc.perform(get("/api/v1/browser-agent/commands/next").param("agentId", AGENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(commandId.toString()))
                .andExpect(jsonPath("$.type").value("OPEN_BROWSER"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.agentId").value(AGENT_ID))
                .andExpect(jsonPath("$.payload.profileId").value(profileId.toString()))
                .andExpect(jsonPath("$.startedAt").exists());
    }

    @Test
    void emptyQueueAnswersWithNoContentAndAnEmptyBody() throws Exception {
        when(commandService.claimNext(AGENT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/browser-agent/commands/next").param("agentId", AGENT_ID))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().isEmpty()));
    }

    @Test
    void requestWithoutAnAgentIdentifierIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/browser-agent/commands/next"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(commandService, never()).claimNext(anyString());
    }

    /**
     * Пустой {@code agentId} не должен закреплять команду за безымянным агентом. Конкретный
     * статус ошибки здесь не фиксируется — важно, что команда не выдаётся.
     */
    @Test
    void blankAgentIdentifierNeverReceivesACommand() throws Exception {
        when(commandService.claimNext(anyString())).thenReturn(Optional.of(command(BrowserAgentCommandStatus.IN_PROGRESS)));

        mockMvc.perform(get("/api/v1/browser-agent/commands/next").param("agentId", "   "))
                .andExpect(result -> assertNotEquals(200, result.getResponse().getStatus(),
                        "пустой идентификатор агента не должен получать команду"));

        verify(commandService, never()).claimNext(anyString());
    }

    @Test
    void commandStateIsAvailableById() throws Exception {
        when(commandService.findById(commandId)).thenReturn(command(BrowserAgentCommandStatus.SUCCEEDED));

        mockMvc.perform(get("/api/v1/browser-agent/commands/{id}", commandId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void scanProgressIsValidatedAndForwarded() throws Exception {
        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/scan-progress", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s","currentPage":2,"totalPages":4,
                                 "collected":200,"totalResults":360}
                                """.formatted(AGENT_ID)))
                .andExpect(status().isOk());

        verify(commandService).updateScanProgress(commandId, AGENT_ID, 2, 4, 200, 360);
    }

    @Test
    void impossibleScanProgressIsRejectedByTheHttpContract() throws Exception {
        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/scan-progress", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s","currentPage":0,"totalPages":21,
                                 "collected":-1,"totalResults":-1}
                                """.formatted(AGENT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

        verify(commandService, never()).updateScanProgress(any(), anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void unknownCommandIsReportedAsNotFoundProblemDetail() throws Exception {
        when(commandService.findById(commandId)).thenThrow(new BrowserAgentCommandNotFoundException(commandId));

        mockMvc.perform(get("/api/v1/browser-agent/commands/{id}", commandId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROWSER_AGENT_COMMAND_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void malformedCommandIdentifierIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/browser-agent/commands/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void resultIsForwardedWithEveryMappedField() throws Exception {
        when(commandService.complete(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command(BrowserAgentCommandStatus.SUCCEEDED));
        String body = """
                {"agentId":"%s","status":"SUCCEEDED",
                 "authorization":{"status":"AUTHORIZED","message":null},
                 "resumes":[{"hhResumeId":"abcdef0123456789abcd","name":"  Java-разработчик  ",
                             "plainText":"Текст резюме"}]}
                """.formatted(AGENT_ID);

        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/result", commandId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        verify(commandService).complete(eq(commandId), eq(AGENT_ID),
                eq(BrowserAgentCommandResultStatus.SUCCEEDED), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void searchResultForwardsPublicationAndVerifiedEnrichment() throws Exception {
        when(commandService.complete(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command(BrowserAgentCommandStatus.SUCCEEDED));
        String body = """
                {"agentId":"%s","status":"SUCCEEDED","vacancies":[{
                  "hhVacancyId":"118123456","title":"Java","companyName":"Example","labels":[],"position":0,
                  "publishedAt":"2026-09-11T17:43:35.994Z",
                  "enrichment":{"workFormats":["REMOTE"],"responsesCount":42,"userTestPresent":true}}]}
                """.formatted(AGENT_ID);

        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/result", commandId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy>> vacancies =
                ArgumentCaptor.forClass(List.class);
        verify(commandService).complete(eq(commandId), eq(AGENT_ID),
                eq(BrowserAgentCommandResultStatus.SUCCEEDED), isNull(), isNull(), isNull(), vacancies.capture(),
                isNull(), isNull(), isNull());
        var vacancy = vacancies.getValue().getFirst();
        assertEquals(Instant.parse("2026-09-11T17:43:35.994Z"), vacancy.publishedAt());
        assertEquals(Boolean.TRUE, vacancy.hasQuestionnaire());
        assertEquals("REMOTE", vacancy.enrichment().path("workFormats").get(0).asString());
        assertEquals(42, vacancy.enrichment().path("responsesCount").asInt());
    }

    @Test
    void applicationOutcomeIsForwardedAsPartOfTheResult() throws Exception {
        when(commandService.complete(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command(BrowserAgentCommandStatus.SUCCEEDED));
        String body = """
                {"agentId":"%s","status":"SUCCEEDED","application":"SENT","applicationBlocker":"CAPTCHA",
                 "vacancyPlainText":"Описание вакансии"}
                """.formatted(AGENT_ID);

        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/result", commandId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(commandService).complete(eq(commandId), eq(AGENT_ID),
                eq(BrowserAgentCommandResultStatus.SUCCEEDED), any(), any(), any(), any(),
                eq(Outcome.SENT), any(), eq("Описание вакансии"));
    }

    @Test
    void resultRequiresAnAgentIdentifierAndAStatus() throws Exception {
        expectResultValidationFailure("{\"status\":\"SUCCEEDED\"}");
        expectResultValidationFailure("{\"agentId\":\"   \",\"status\":\"SUCCEEDED\"}");
        expectResultValidationFailure("{\"agentId\":\"%s\"}".formatted(AGENT_ID));
        expectResultValidationFailure("{\"agentId\":\"%s\",\"status\":\"MAYBE\"}".formatted(AGENT_ID));

        verify(commandService, never()).complete(any(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void oversizedResultPayloadIsRejected() throws Exception {
        expectResultValidationFailure("""
                {"agentId":"%s","status":"FAILED","errorMessage":"%s"}
                """.formatted(AGENT_ID, "e".repeat(1001)));

        String tooManyResumes = IntStream.range(0, 101)
                .mapToObj(index -> """
                        {"hhResumeId":"abcdef0123456789abcd","name":"Резюме"}""")
                .collect(Collectors.joining(","));
        expectResultValidationFailure("""
                {"agentId":"%s","status":"SUCCEEDED","resumes":[%s]}
                """.formatted(AGENT_ID, tooManyResumes));
    }

    @Test
    void malformedVacancyInsideTheResultIsRejected() throws Exception {
        expectResultValidationFailure("""
                {"agentId":"%s","status":"SUCCEEDED","vacancies":[
                  {"hhVacancyId":"abc","title":"Java","companyName":"Example","labels":[],"position":0}]}
                """.formatted(AGENT_ID));
        expectResultValidationFailure("""
                {"agentId":"%s","status":"SUCCEEDED","vacancies":[
                  {"hhVacancyId":"118123456","title":"Java","companyName":"Example","labels":[],"position":2000}]}
                """.formatted(AGENT_ID));
    }

    @Test
    void conflictFromTheServiceIsReportedAsAConflictProblemDetail() throws Exception {
        when(commandService.complete(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BrowserAgentCommandConflictException("Команда назначена другому Browser Agent."));

        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/result", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"%s\",\"status\":\"SUCCEEDED\"}".formatted(AGENT_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROWSER_AGENT_COMMAND_CONFLICT"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void domainRejectionOfAResultIsReportedAsAValidationProblem() throws Exception {
        when(commandService.complete(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidBrowserAgentDataException("Результат поиска недопустим для этой команды."));

        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/result", commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"%s\",\"status\":\"SUCCEEDED\"}".formatted(AGENT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BROWSER_AGENT_DATA"));
    }

    @Test
    void preparedLetterIsForwardedAndAnsweredWithTheDecision() throws Exception {
        when(commandService.prepareApplicationLetter(eq(commandId), eq(AGENT_ID), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/application-letter", commandId)
                        .param("agentId", AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Обычное письмо","source":"FALLBACK","errorCode":"GENERATION_TIMEOUT"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));

        verify(commandService).prepareApplicationLetter(commandId, AGENT_ID,
                new PreparedLetter("Обычное письмо", "FALLBACK", "GENERATION_TIMEOUT", null));
    }

    @Test
    void letterRequestRequiresTextAndSource() throws Exception {
        expectLetterValidationFailure("{\"source\":\"STATIC\"}");
        expectLetterValidationFailure("{\"text\":\"Обычное письмо\",\"source\":\"  \"}");
        expectLetterValidationFailure("{\"text\":\"Обычное письмо\",\"source\":\"%s\"}".formatted("S".repeat(17)));

        verify(commandService, never()).prepareApplicationLetter(any(), anyString(), any());
    }

    @Test
    void continuationCheckAndSubmitAnswerWithTheDecisionOfTheService() throws Exception {
        when(commandService.applicationControl(commandId, AGENT_ID, false)).thenReturn(true);
        when(commandService.applicationControl(commandId, AGENT_ID, true)).thenReturn(false);

        mockMvc.perform(get("/api/v1/browser-agent/commands/{id}/application-control", commandId)
                        .param("agentId", AGENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));

        mockMvc.perform(post("/api/v1/browser-agent/commands/{id}/application-submit", commandId)
                        .param("agentId", AGENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));

        verify(commandService).applicationControl(commandId, AGENT_ID, false);
        verify(commandService).applicationControl(commandId, AGENT_ID, true);
    }

    @Test
    void applicationControlOfAnUnknownCommandIsReportedAsNotFound() throws Exception {
        when(commandService.applicationControl(any(), anyString(), anyBoolean()))
                .thenThrow(new BrowserAgentCommandNotFoundException(commandId));

        mockMvc.perform(get("/api/v1/browser-agent/commands/{id}/application-control", commandId)
                        .param("agentId", AGENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROWSER_AGENT_COMMAND_NOT_FOUND"));
    }

    private void expectResultValidationFailure(String body) throws Exception {
        expectValidationFailure(post("/api/v1/browser-agent/commands/{id}/result", commandId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expectLetterValidationFailure(String body) throws Exception {
        expectValidationFailure(post("/api/v1/browser-agent/commands/{id}/application-letter", commandId)
                .param("agentId", AGENT_ID).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expectValidationFailure(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
}
