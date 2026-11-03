package org.hhclicker.backend.browser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.browser.controller.BrowserController;
import org.hhclicker.backend.browser.exception.*;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.service.BrowserPageData;
import org.hhclicker.backend.browser.service.BrowserProfileService;
import org.hhclicker.backend.browser.service.BrowserRuntimeSnapshot;
import org.hhclicker.backend.shared.exception.GlobalExceptionHandler;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт страницы «Браузер» и команд управления Chrome.
 */
class BrowserControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final String HH_RESUME_ID = "abcdef0123456789abcd";

    private final BrowserProfileService service = mock(BrowserProfileService.class);

    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID commandId = UUID.randomUUID();

    private final BrowserProfile profile = new BrowserProfile(profileId, accountId, CREATED_AT, CREATED_AT);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BrowserController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private BrowserPageData page(boolean browserOpen, UUID activeProfileId, BrowserProfile stored) {
        return new BrowserPageData(new BrowserRuntimeSnapshot("READY", "1.2.3", browserOpen, activeProfileId),
                AuthorizationStatus.AUTHORIZED, stored);
    }

    private String accountBody() {
        return "{\"accountId\":\"%s\"}".formatted(accountId);
    }

    // --- GET /api/v1/browser ---------------------------------------------------------------

    @Test
    void pageOfAnAccountWithARunningProfileIsReturned() throws Exception {
        when(service.loadPage(accountId)).thenReturn(page(true, profileId, profile));

        mockMvc.perform(get("/api/v1/browser").param("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.status").value("READY"))
                .andExpect(jsonPath("$.agent.authorizationStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$.agent.version").value("1.2.3"))
                .andExpect(jsonPath("$.agent.endpoint").value("HTTP polling"))
                .andExpect(jsonPath("$.agent.host").value("Локальный компьютер"))
                .andExpect(jsonPath("$.agent.browserVersion").value("Google Chrome · Playwright"))
                .andExpect(jsonPath("$.profile.id").value(profileId.toString()))
                .andExpect(jsonPath("$.profile.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.profile.processStatus").value("RUNNING"))
                .andExpect(jsonPath("$.profile.profileDirectory").value("profiles/" + profileId))
                .andExpect(jsonPath("$.profile.tags.length()").value(0))
                .andExpect(jsonPath("$.profile.lastStartedAt").doesNotExist());
    }

    @Test
    void pageOfAnAccountWithoutAProfileExposesNoProfileBlock() throws Exception {
        when(service.loadPage(accountId)).thenReturn(page(false, null, null));

        mockMvc.perform(get("/api/v1/browser").param("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.status").value("READY"))
                .andExpect(jsonPath("$.profile").doesNotExist());
    }

    @Test
    void pageRequestWithoutAnAccountIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/browser"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));

        verify(service, never()).loadPage(any());
    }

    @Test
    void pageRequestWithAMalformedAccountIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/browser").param("accountId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void unknownAccountIsReportedAsNotFoundProblemDetail() throws Exception {
        when(service.loadPage(accountId)).thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(get("/api/v1/browser").param("accountId", accountId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    // --- POST /api/v1/browser/profile -------------------------------------------------------

    @Test
    void profileCreationAnswersWithCreatedStatusAndTheFreshProfile() throws Exception {
        when(service.create(accountId)).thenReturn(profile);
        when(service.loadPage(accountId)).thenReturn(page(false, null, profile));

        mockMvc.perform(post("/api/v1/browser/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(profileId.toString()))
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.processStatus").value("STOPPED"))
                .andExpect(jsonPath("$.profileDirectory").value("profiles/" + profileId));

        verify(service).create(accountId);
        verify(service).loadPage(accountId);
    }

    @Test
    void freshProfileIsReportedAsRunningWhenTheAgentAlreadyOpenedIt() throws Exception {
        when(service.create(accountId)).thenReturn(profile);
        when(service.loadPage(accountId)).thenReturn(page(true, profileId, profile));

        mockMvc.perform(post("/api/v1/browser/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processStatus").value("RUNNING"));
    }

    @Test
    void profileCreationRequiresAnAccount() throws Exception {
        expectValidationFailure(post("/api/v1/browser/profile")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        expectValidationFailure(post("/api/v1/browser/profile")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":null}"));

        verify(service, never()).create(any());
    }

    @Test
    void existingProfileIsReportedAsConflictProblemDetail() throws Exception {
        when(service.create(accountId)).thenThrow(new BrowserProfileAlreadyExistsException());

        mockMvc.perform(post("/api/v1/browser/profile")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROWSER_PROFILE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));

        verify(service, never()).loadPage(any());
    }

    // --- start / stop -----------------------------------------------------------------------

    @Test
    void startAnswersWithAcceptedAndTheCommandIdentifier() throws Exception {
        when(service.open(accountId)).thenReturn(commandId);

        mockMvc.perform(post("/api/v1/browser/start")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));

        verify(service).open(accountId);
    }

    @Test
    void startWithoutAnAccountIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/browser/start")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        verify(service, never()).open(any());
    }

    @Test
    void startOfAnAccountWithoutAProfileIsReportedAsNotFound() throws Exception {
        when(service.open(accountId)).thenThrow(new BrowserProfileNotFoundException(accountId));

        mockMvc.perform(post("/api/v1/browser/start")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROWSER_PROFILE_NOT_FOUND"));
    }

    /**
     * Недоступный Browser Agent — внешняя система, поэтому контракт отдаёт 502.
     */
    @Test
    void unavailableAgentIsReportedAsABadGateway() throws Exception {
        when(service.open(accountId)).thenThrow(new BrowserRuntimeUnavailableException());

        mockMvc.perform(post("/api/v1/browser/start")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BROWSER_AGENT_UNAVAILABLE"))
                .andExpect(jsonPath("$.category").value("EXTERNAL_SYSTEM"));
    }

    @Test
    void stopAnswersWithAcceptedAndTheCommandIdentifier() throws Exception {
        when(service.close()).thenReturn(commandId);

        mockMvc.perform(post("/api/v1/browser/stop"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));

        verify(service).close();
    }

    @Test
    void stopWithAnUnavailableAgentIsReportedAsABadGateway() throws Exception {
        when(service.close()).thenThrow(new BrowserRuntimeUnavailableException());

        mockMvc.perform(post("/api/v1/browser/stop"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BROWSER_AGENT_UNAVAILABLE"));
    }

    // --- check-authorization / discover-resumes ---------------------------------------------

    @Test
    void authorizationCheckAnswersWithAcceptedAndTheCommandIdentifier() throws Exception {
        when(service.checkAuthorization(accountId)).thenReturn(commandId);

        mockMvc.perform(post("/api/v1/browser/check-authorization")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));

        verify(service).checkAuthorization(accountId);
    }

    @Test
    void authorizationCheckWithoutAnAccountIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/browser/check-authorization")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        verify(service, never()).checkAuthorization(any());
    }

    @Test
    void authorizationCheckWithoutARunningBrowserIsReportedAsConflict() throws Exception {
        when(service.checkAuthorization(accountId)).thenThrow(new BrowserNotRunningException());

        mockMvc.perform(post("/api/v1/browser/check-authorization")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROWSER_NOT_RUNNING"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void resumeDiscoveryAnswersWithAcceptedAndCallsItsOwnServiceMethod() throws Exception {
        when(service.discoverResumes(accountId)).thenReturn(commandId);

        mockMvc.perform(post("/api/v1/browser/discover-resumes")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));

        verify(service).discoverResumes(accountId);
        verify(service, never()).checkAuthorization(any());
    }

    @Test
    void resumeDiscoveryValidatesItsBodyAndReportsDomainErrors() throws Exception {
        expectValidationFailure(post("/api/v1/browser/discover-resumes")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        when(service.discoverResumes(accountId)).thenThrow(new BrowserNotRunningException());
        mockMvc.perform(post("/api/v1/browser/discover-resumes")
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROWSER_NOT_RUNNING"));
    }

    // --- open-url ---------------------------------------------------------------------------

    @Test
    void urlIsForwardedWithoutLosingAnyCharacter() throws Exception {
        when(service.openUrl(any(), anyString())).thenReturn(commandId);
        String url = "https://hh.ru/search/vacancy?text=java&area=1#results";

        mockMvc.perform(post("/api/v1/browser/open-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"url\":\"%s\"}".formatted(accountId, url)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));

        verify(service).openUrl(accountId, url);
    }

    @Test
    void openUrlRequiresBothAnAccountAndANonEmptyLink() throws Exception {
        expectValidationFailure(post("/api/v1/browser/open-url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://hh.ru/\"}"));
        expectValidationFailure(post("/api/v1/browser/open-url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\"}".formatted(accountId)));
        expectValidationFailure(post("/api/v1/browser/open-url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"url\":\"   \"}".formatted(accountId)));
        expectValidationFailure(post("/api/v1/browser/open-url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"url\":\"https://hh.ru/%s\"}"
                        .formatted(accountId, "a".repeat(2048))));

        verify(service, never()).openUrl(any(), anyString());
    }

    @Test
    void linkRejectedByTheDomainIsReportedAsAValidationProblem() throws Exception {
        when(service.openUrl(any(), anyString())).thenThrow(new InvalidBrowserUrlException());

        mockMvc.perform(post("/api/v1/browser/open-url").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"url\":\"https://example.com\"}".formatted(accountId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BROWSER_URL"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }

    @Test
    void openUrlWithoutARunningBrowserIsReportedAsConflict() throws Exception {
        when(service.openUrl(any(), anyString())).thenThrow(new BrowserNotRunningException());

        mockMvc.perform(post("/api/v1/browser/open-url").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"url\":\"https://hh.ru/\"}".formatted(accountId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BROWSER_NOT_RUNNING"));
    }

    // --- resume-text -------------------------------------------------------------------------

    @Test
    void resumeTextRequestForwardsBothIdentifiers() throws Exception {
        when(service.readResumeText(accountId, HH_RESUME_ID)).thenReturn(commandId);

        mockMvc.perform(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"hhResumeId\":\"%s\"}".formatted(accountId, HH_RESUME_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()));

        verify(service).readResumeText(accountId, HH_RESUME_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdef0123456789abc",
            "ABCDEF0123456789ABCD",
            "abcdefg123456789abcd",
            "abcdef0123456789ab-d",
            "   "
    })
    void malformedResumeIdentifierIsRejectedOnTheHttpBoundary(String hhResumeId) throws Exception {
        expectValidationFailure(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"hhResumeId\":\"%s\"}".formatted(accountId, hhResumeId)));

        verify(service, never()).readResumeText(any(), anyString());
    }

    @Test
    void missingIdentifiersOfTheResumeTextRequestAreRejected() throws Exception {
        expectValidationFailure(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"hhResumeId\":\"%s\"}".formatted(HH_RESUME_ID)));
        expectValidationFailure(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\"}".formatted(accountId)));
        expectValidationFailure(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"hhResumeId\":null}".formatted(accountId)));
        expectValidationFailure(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"hhResumeId\":\"%s\"}".formatted(accountId, "a".repeat(101))));

        verify(service, never()).readResumeText(any(), anyString());
    }

    @Test
    void resumeIdentifierLengthBoundsAreAccepted() throws Exception {
        when(service.readResumeText(any(), anyString())).thenReturn(commandId);

        mockMvc.perform(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"hhResumeId\":\"%s\"}"
                                .formatted(accountId, "a".repeat(20))))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/browser/resume-text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"hhResumeId\":\"%s\"}"
                                .formatted(accountId, "a".repeat(100))))
                .andExpect(status().isAccepted());
    }

    @Test
    void malformedJsonBodyIsRejected() throws Exception {
        expectValidationFailure(post("/api/v1/browser/start")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":"));
    }

    private void expectValidationFailure(RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
}
