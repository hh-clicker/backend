package org.hhclicker.backend.overview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.overview.controller.OverviewController;
import org.hhclicker.backend.overview.service.OverviewData;
import org.hhclicker.backend.overview.service.OverviewMetric;
import org.hhclicker.backend.overview.service.OverviewRecentSearch;
import org.hhclicker.backend.overview.service.OverviewService;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone HTTP-контракт overview с настоящим глобальным обработчиком ошибок.
 */
class OverviewControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RESUME_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SEARCH_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private final OverviewService service = mock(OverviewService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OverviewController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void requestWithoutParametersPassesNullsAndSerializesNullableProjectionFields() throws Exception {
        when(service.load(null, null)).thenReturn(emptyData());

        mockMvc.perform(get("/api/v1/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(0))
                .andExpect(jsonPath("$.selectedAccountId").value(nullValue()))
                .andExpect(jsonPath("$.resumes.length()").value(0))
                .andExpect(jsonPath("$.selectedResumeId").value(nullValue()))
                .andExpect(jsonPath("$.activeRun").value(nullValue()))
                .andExpect(jsonPath("$.agentStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.authorizationStatus").value("UNKNOWN"));

        verify(service).load(null, null);
    }

    @Test
    void requestWithAccountOnlyPassesAccountAndNullResumeWithoutChanges() throws Exception {
        when(service.load(ACCOUNT_ID, null)).thenReturn(emptyData());

        mockMvc.perform(get("/api/v1/overview").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk());

        verify(service).load(ACCOUNT_ID, null);
    }

    @Test
    void requestWithResumeOnlyPassesNullAccountAndResumeWithoutChanges() throws Exception {
        when(service.load(null, RESUME_ID)).thenReturn(emptyData());

        mockMvc.perform(get("/api/v1/overview").param("resumeId", RESUME_ID.toString()))
                .andExpect(status().isOk());

        verify(service).load(null, RESUME_ID);
    }

    @Test
    void requestWithBothParametersPassesBothWithoutChangesAndExposesCoreProjection() throws Exception {
        when(service.load(ACCOUNT_ID, RESUME_ID)).thenReturn(populatedData());

        mockMvc.perform(get("/api/v1/overview")
                        .param("accountId", ACCOUNT_ID.toString())
                        .param("resumeId", RESUME_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].id").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.accounts[0].name").value("Основной"))
                .andExpect(jsonPath("$.selectedAccountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.resumes[0].id").value(RESUME_ID.toString()))
                .andExpect(jsonPath("$.selectedResumeId").value(RESUME_ID.toString()))
                .andExpect(jsonPath("$.authorizationStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$.metrics[0].id").value("new"))
                .andExpect(jsonPath("$.metrics[0].value").value(7))
                .andExpect(jsonPath("$.recentSearches[0].id").value(SEARCH_ID.toString()))
                .andExpect(jsonPath("$.recentSearches[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.activeRun").value(nullValue()));

        verify(service).load(ACCOUNT_ID, RESUME_ID);
    }

    @Test
    void malformedAccountIdIsARequestValidationFailure() throws Exception {
        mockMvc.perform(get("/api/v1/overview").param("accountId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));

        verifyNoInteractions(service);
    }

    @Test
    void malformedResumeIdIsARequestValidationFailure() throws Exception {
        mockMvc.perform(get("/api/v1/overview").param("resumeId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));

        verifyNoInteractions(service);
    }

    @Test
    void domainExceptionUsesTheGlobalProblemDetailContract() throws Exception {
        when(service.load(null, null)).thenThrow(new ApplicationException(
                ApplicationErrorCategory.NOT_FOUND, "OVERVIEW_CONTEXT_NOT_FOUND", "Контекст не найден."));

        mockMvc.perform(get("/api/v1/overview"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OVERVIEW_CONTEXT_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Контекст не найден."))
                .andExpect(jsonPath("$.instance").value("/api/v1/overview"));
    }

    @Test
    void unexpectedExceptionUsesTheGenericProblemDetailContract() throws Exception {
        when(service.load(null, null)).thenThrow(new IllegalStateException("database password must stay hidden"));

        mockMvc.perform(get("/api/v1/overview"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.category").value("INTERNAL"))
                .andExpect(jsonPath("$.detail").value("Не удалось выполнить операцию."));
    }

    private OverviewData emptyData() {
        return new OverviewData(List.of(), null, List.of(), null, List.of(), List.of(), List.of(), List.of());
    }

    private OverviewData populatedData() {
        HhAccount account = new HhAccount(ACCOUNT_ID, "Основной", "hh-user", AuthorizationStatus.AUTHORIZED,
                CREATED_AT, CREATED_AT);
        HhResume resume = new HhResume(RESUME_ID, ACCOUNT_ID, "abcdef0123456789abcd", "Java",
                ResumeAutomationSettings.defaults(), CREATED_AT, CREATED_AT);
        ResumeProfile profile = new ResumeProfile(resume, account.name(), ResumeStatistics.empty());
        return new OverviewData(List.of(account), account, List.of(profile), profile,
                List.of(new OverviewMetric("new", "Новые вакансии", 7, "1 сохранённых поисков", "purple")),
                List.of(new OverviewRecentSearch(SEARCH_ID, "Java remote", CREATED_AT, 7, "ACTIVE")),
                List.of(), List.of());
    }
}
