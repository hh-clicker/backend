package org.hhclicker.backend.resume;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.resume.controller.ResumeController;
import org.hhclicker.backend.resume.exception.InvalidResumeDataException;
import org.hhclicker.backend.resume.exception.ResumeHasDependentDataException;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ImportResumeItem;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.resume.service.UpdateResumeSettingsCommand;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт резюме. Контроллер поднимается с настоящим {@link GlobalExceptionHandler},
 * поэтому проверяются и Bean Validation запроса, и отображение доменных ошибок в ProblemDetail.
 */
class ResumeControllerTests {

    private static final String HASH = "abcdef0123456789abcd";
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant TEXT_LOADED_AT = Instant.parse("2026-09-04T09:00:00Z");

    private final ResumeService resumeService = mock(ResumeService.class);

    private final UUID resumeId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ResumeController(resumeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ResumeProfile profile() {
        var settings = new ResumeAutomationSettings("Cover letter", 42, 20, 60, false,
                new AiLetterSettings(true, "Hello!\n#AI_CHANGE#\nBye!", 1200));
        var resume = new HhResume(resumeId, accountId, HASH, "Java Developer", settings,
                CREATED_AT, CREATED_AT, "Plain resume text", TEXT_LOADED_AT);
        return new ResumeProfile(resume, "Primary account", new ResumeStatistics(2, 7, 3, 1, 4));
    }

    @Test
    void listWithoutAccountIdAsksForResumesOfEveryAccount() throws Exception {
        when(resumeService.findAll(null)).thenReturn(List.of(profile()));

        mockMvc.perform(get("/api/v1/resumes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(resumeId.toString()));

        verify(resumeService).findAll(null);
    }

    @Test
    void listWithAccountIdPassesTheFilterToTheService() throws Exception {
        when(resumeService.findAll(accountId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/resumes").param("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        verify(resumeService).findAll(accountId);
    }

    @Test
    void singleResumeIsMappedIntoTheFullResponse() throws Exception {
        when(resumeService.findById(resumeId)).thenReturn(profile());

        mockMvc.perform(get("/api/v1/resumes/{id}", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resumeId.toString()))
                .andExpect(jsonPath("$.hhResumeId").value(HASH))
                .andExpect(jsonPath("$.name").value("Java Developer"))
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.accountName").value("Primary account"))
                .andExpect(jsonPath("$.publicUrl").value("https://hh.ru/resume/" + HASH))
                .andExpect(jsonPath("$.plainText").value("Plain resume text"))
                .andExpect(jsonPath("$.textLoadedAt").exists())
                .andExpect(jsonPath("$.settings.coverLetter").value("Cover letter"))
                .andExpect(jsonPath("$.settings.dailyLimit").value(42))
                .andExpect(jsonPath("$.settings.pauseFromSeconds").value(20))
                .andExpect(jsonPath("$.settings.pauseToSeconds").value(60))
                .andExpect(jsonPath("$.settings.skipQuestionnaires").value(false))
                .andExpect(jsonPath("$.settings.aiLetter.enabled").value(true))
                .andExpect(jsonPath("$.settings.aiLetter.maxLength").value(1200))
                .andExpect(jsonPath("$.statistics.searches").value(2))
                .andExpect(jsonPath("$.statistics.newVacancies").value(7))
                .andExpect(jsonPath("$.statistics.queuedVacancies").value(3))
                .andExpect(jsonPath("$.statistics.successfulApplications").value(1))
                .andExpect(jsonPath("$.statistics.blockedCompanies").value(4));
    }

    @Test
    void unknownResumeIsReportedAsNotFoundProblemDetail() throws Exception {
        when(resumeService.findById(resumeId)).thenThrow(new ResumeNotFoundException(resumeId));

        mockMvc.perform(get("/api/v1/resumes/{id}", resumeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void malformedResumeIdIsRejectedAsRequestValidation() throws Exception {
        mockMvc.perform(get("/api/v1/resumes/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void importMapsRequestItemsIntoServiceCommands() throws Exception {
        when(resumeService.importResumes(any(), anyList())).thenReturn(List.of(profile()));

        mockMvc.perform(post("/api/v1/resumes/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(HASH, "Java Developer", "Plain resume text")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].hhResumeId").value(HASH));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImportResumeItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(resumeService).importResumes(org.mockito.ArgumentMatchers.eq(accountId), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(new ImportResumeItem(HASH, "Java Developer", "Plain resume text"), captor.getValue().getFirst());
    }

    @Test
    void importWithoutAccountIdIsRejected() throws Exception {
        String body = """
                {"items":[{"hhResumeId":"%s","name":"Java Developer","plainText":"Plain resume text"}]}
                """.formatted(HASH);

        expectValidationFailure(post("/api/v1/resumes/import").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void importWithEmptyItemListIsRejected() throws Exception {
        String body = """
                {"accountId":"%s","items":[]}
                """.formatted(accountId);

        expectValidationFailure(post("/api/v1/resumes/import").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void importWithMalformedHashIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/resumes/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(importBody("NOT-A-HASH", "Java Developer", "Plain resume text")));
    }

    @Test
    void importWithBlankPlainTextIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/resumes/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(importBody(HASH, "Java Developer", "   ")));
    }

    @Test
    void importAboveOneHundredItemsIsRejected() throws Exception {
        String items = IntStream.range(0, 101)
                .mapToObj(index -> """
                        {"hhResumeId":"%020d","name":"Resume %d","plainText":"Plain resume text"}"""
                        .formatted(index, index))
                .collect(Collectors.joining(","));
        String body = """
                {"accountId":"%s","items":[%s]}
                """.formatted(accountId, items);

        expectValidationFailure(post("/api/v1/resumes/import").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void importRejectedByTheServiceIsReportedAsBadRequestProblemDetail() throws Exception {
        when(resumeService.importResumes(any(), anyList()))
                .thenThrow(new InvalidResumeDataException("Resume already belongs to another account."));

        mockMvc.perform(post("/api/v1/resumes/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(HASH, "Java Developer", "Plain resume text")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESUME_DATA"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void updateSettingsMapsRequestIntoTheServiceCommand() throws Exception {
        when(resumeService.updateSettings(any(), any())).thenReturn(profile());

        mockMvc.perform(put("/api/v1/resumes/{id}/settings", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsBody("Cover letter", 42, 20, 60)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resumeId.toString()));

        ArgumentCaptor<UpdateResumeSettingsCommand> captor = ArgumentCaptor.forClass(UpdateResumeSettingsCommand.class);
        verify(resumeService).updateSettings(org.mockito.ArgumentMatchers.eq(resumeId), captor.capture());
        UpdateResumeSettingsCommand command = captor.getValue();
        assertEquals("Cover letter", command.coverLetter());
        assertEquals(42, command.dailyLimit());
        assertEquals(20, command.pauseFromSeconds());
        assertEquals(60, command.pauseToSeconds());
        assertTrue(command.skipQuestionnaires());
        assertEquals(new AiLetterSettings(true, "Hello!\n#AI_CHANGE#\nBye!", 1200), command.aiLetter());
    }

    @Test
    void updateSettingsWithoutGenerationBlockPassesNullThrough() throws Exception {
        when(resumeService.updateSettings(any(), any())).thenReturn(profile());
        String body = """
                {"coverLetter":"Cover letter","dailyLimit":20,"pauseFromSeconds":30,
                 "pauseToSeconds":90,"skipQuestionnaires":true}
                """;

        mockMvc.perform(put("/api/v1/resumes/{id}/settings", resumeId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateResumeSettingsCommand> captor = ArgumentCaptor.forClass(UpdateResumeSettingsCommand.class);
        verify(resumeService).updateSettings(org.mockito.ArgumentMatchers.eq(resumeId), captor.capture());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().aiLetter(),
                "контроллер не подставляет настройки генерации — это делает сервис");
    }

    @Test
    void updateSettingsWithoutCoverLetterIsRejected() throws Exception {
        String body = """
                {"dailyLimit":20,"pauseFromSeconds":30,"pauseToSeconds":90,"skipQuestionnaires":true}
                """;

        expectValidationFailure(put("/api/v1/resumes/{id}/settings", resumeId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        verify(resumeService, never()).updateSettings(any(), any());
    }

    @Test
    void updateSettingsAboveTheDailyLimitMaximumIsRejected() throws Exception {
        expectValidationFailure(put("/api/v1/resumes/{id}/settings", resumeId)
                .contentType(MediaType.APPLICATION_JSON).content(settingsBody("Cover letter", 501, 30, 90)));
        verify(resumeService, never()).updateSettings(any(), any());
    }

    @Test
    void updateSettingsWithTooShortPauseIsRejected() throws Exception {
        expectValidationFailure(put("/api/v1/resumes/{id}/settings", resumeId)
                .contentType(MediaType.APPLICATION_JSON).content(settingsBody("Cover letter", 20, 9, 90)));
        verify(resumeService, never()).updateSettings(any(), any());
    }

    @Test
    void deletingAResumeReturnsNoContentWithoutABody() throws Exception {
        doNothing().when(resumeService).delete(resumeId);

        mockMvc.perform(delete("/api/v1/resumes/{id}", resumeId))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().isEmpty(),
                        "204 не должен содержать тело ответа"));

        verify(resumeService).delete(resumeId);
    }

    @Test
    void deletingAResumeWithDependentDataIsReportedAsConflictProblemDetail() throws Exception {
        doThrow(new ResumeHasDependentDataException(new RuntimeException("fk_vacancy_resume")))
                .when(resumeService).delete(resumeId);

        mockMvc.perform(delete("/api/v1/resumes/{id}", resumeId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESUME_HAS_DEPENDENT_DATA"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void deletingAMissingResumeIsReportedAsNotFoundProblemDetail() throws Exception {
        doThrow(new ResumeNotFoundException(resumeId)).when(resumeService).delete(resumeId);

        mockMvc.perform(delete("/api/v1/resumes/{id}", resumeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));
    }

    private void expectValidationFailure(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    private String importBody(String hash, String name, String plainText) {
        return """
                {"accountId":"%s","items":[{"hhResumeId":"%s","name":"%s","plainText":"%s"}]}
                """.formatted(accountId, hash, name, plainText);
    }

    private String settingsBody(String coverLetter, int dailyLimit, int pauseFrom, int pauseTo) {
        return """
                {"coverLetter":"%s","dailyLimit":%d,"pauseFromSeconds":%d,"pauseToSeconds":%d,
                 "skipQuestionnaires":true,
                 "aiLetter":{"enabled":true,"template":"Hello!\\n#AI_CHANGE#\\nBye!","maxLength":1200}}
                """.formatted(coverLetter, dailyLimit, pauseFrom, pauseTo);
    }
}
