package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandType;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.PreparedSearchScan;
import org.hhclicker.backend.search.service.SearchProfile;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Создание команд Browser Agent: payload, начальный статус, дедупликация и блокировки.
 */
class BrowserAgentCommandCreationTests extends BrowserAgentTestSupport {

    private static final String SCAN_URL =
            "https://hh.ru/search/vacancy?items_on_page=100&order_by=publication_time&page=0&text=java";

    private BrowserAgentCommand command(UUID id) {
        return service.findById(id);
    }

    private SearchProfile searchProfile() {
        VacancySearch search = new VacancySearch(searchId, resumeId, "Java в Москве", SCAN_URL,
                "a".repeat(64), 7, SearchStatus.SCANNING, 0, 0, null, null, START, START);
        return new SearchProfile(search, "Java-разработчик");
    }

    private void searchIsReadyToScan() {
        when(searches.accountIdForSearch(searchId)).thenReturn(accountId);
        when(searches.scan(searchId)).thenReturn(new PreparedSearchScan(searchProfile(), SCAN_URL));
    }

    @Test
    void openBrowserCommandIsQueuedWithOnlyTheProfileInThePayload() {
        BrowserAgentCommand queued = command(service.open(profile));

        assertEquals(BrowserAgentCommandType.OPEN_BROWSER, queued.type());
        assertEquals(BrowserAgentCommandStatus.QUEUED, queued.status());
        assertEquals(profileId, queued.payload().profileId());
        assertNull(queued.payload().url());
        assertNull(queued.payload().searchId());
        assertNull(queued.payload().application());
        assertEquals(START, queued.createdAt());
        assertNull(queued.agentId(), "команда ещё никому не принадлежит");
        assertNull(queued.startedAt());
        assertNull(queued.completedAt());
        assertNull(queued.errorMessage());
    }

    @Test
    void closeBrowserCommandCarriesNoPayloadAtAll() {
        BrowserAgentCommand queued = command(service.close());

        assertEquals(BrowserAgentCommandType.CLOSE_BROWSER, queued.type());
        assertNull(queued.payload());
    }

    @Test
    void openUrlCommandCarriesTheRequestedAddress() {
        BrowserAgentCommand queued = command(service.openUrl(profile, "https://hh.ru/applicant/resumes"));

        assertEquals(BrowserAgentCommandType.OPEN_URL, queued.type());
        assertEquals(profileId, queued.payload().profileId());
        assertEquals("https://hh.ru/applicant/resumes", queued.payload().url());
    }

    @Test
    void questionnaireApplicationInspectionCarriesOnlyItsReadOnlyTarget() {
        questionnaireVacancyIsReady();

        BrowserAgentCommand queued = command(service.verifyQuestionnaireApplication(vacancyId));

        assertEquals(BrowserAgentCommandType.INSPECT_APPLICATION, queued.type());
        assertEquals(profileId, queued.payload().profileId());
        assertEquals(vacancyId, queued.payload().vacancyId());
        assertEquals("https://hh.ru/vacancy/118123456", queued.payload().url());
        assertNull(queued.payload().application());
    }

    @Test
    void authorizationCheckAndResumeDiscoveryCarryOnlyTheProfile() {
        assertEquals(BrowserAgentCommandType.CHECK_AUTHORIZATION, command(service.checkAuthorization(profile)).type());

        BrowserAgentCommand discovery = command(service.discoverResumes(profile));
        assertEquals(BrowserAgentCommandType.DISCOVER_RESUMES, discovery.type());
        assertEquals(profileId, discovery.payload().profileId());
        assertNull(discovery.payload().url());
    }

    @Test
    void readingResumeTextReusesResumeDiscoveryWithAPrintablePage() {
        BrowserAgentCommand queued = command(service.readResumeText(profile, HH_RESUME_ID));

        assertEquals(BrowserAgentCommandType.DISCOVER_RESUMES, queued.type(),
                "чтение текста резюме — та же команда агента, но с конкретным адресом");
        assertEquals("https://hh.ru/resume/" + HH_RESUME_ID + "?print=true", queued.payload().url());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ABCDEF0123456789ABCD", "abcdef0123456789abc", "zzzzzzzzzzzzzzzzzzzz"})
    void malformedResumeIdentifierIsRejectedBeforeAnyCommandIsCreated(String hhResumeId) {
        assertInvalidAgentData(() -> service.readResumeText(profile, hhResumeId));
    }

    @Test
    void missingResumeIdentifierIsRejected() {
        assertInvalidAgentData(() -> service.readResumeText(profile, null));
        assertInvalidAgentData(() -> service.readResumeText(profile, "a".repeat(101)));
    }

    @Test
    void repeatedRequestOfTheSameCommandReturnsTheAlreadyQueuedOne() {
        UUID first = service.open(profile);

        assertEquals(first, service.open(profile), "дубль не создаёт вторую команду того же профиля");
    }

    @Test
    void commandsOfDifferentProfilesAreQueuedSeparately() {
        BrowserProfile otherProfile = new BrowserProfile(otherProfileId, UUID.randomUUID(), START, START);

        assertNotEquals(service.open(profile), service.open(otherProfile));
    }

    @Test
    void closeBrowserIsDeduplicatedEvenWithoutAPayload() {
        UUID first = service.close();

        assertEquals(first, service.close());
    }

    @Test
    void openUrlOfAnotherAddressCreatesASeparateCommand() {
        UUID first = service.openUrl(profile, "https://hh.ru/applicant/resumes");

        assertNotEquals(first, service.openUrl(profile, "https://hh.ru/search/vacancy"));
    }

    @Test
    void browserControlIsBlockedWhileApplicationsAreRunning() {
        when(applications.busy()).thenReturn(true);
        questionnaireVacancyIsReady();

        assertConflict(() -> service.open(profile));
        assertConflict(() -> service.close());
        assertConflict(() -> service.openUrl(profile, "https://hh.ru/applicant/resumes"));
        assertConflict(() -> service.checkAuthorization(profile));
        assertConflict(() -> service.discoverResumes(profile));
        assertConflict(() -> service.verifyQuestionnaireApplication(vacancyId));
    }

    @Test
    void questionnaireInspectionIsAllowedWhileARunIsPaused() {
        when(applications.busy()).thenReturn(true);
        when(applications.inspectionAllowed(profileId)).thenReturn(true);
        questionnaireVacancyIsReady();

        assertEquals(BrowserAgentCommandType.INSPECT_APPLICATION,
                command(service.verifyQuestionnaireApplication(vacancyId)).type());
    }

    @Test
    void manualInspectionIsAllowedWhileAPausedRunKeepsTheInstallationBusy() {
        when(applications.busy()).thenReturn(true);
        when(applications.inspectionAllowed(profileId)).thenReturn(true);

        assertEquals(BrowserAgentCommandType.OPEN_BROWSER, command(service.open(profile)).type());
        assertEquals(BrowserAgentCommandType.OPEN_URL,
                command(service.openUrl(profile, "https://hh.ru/applicant/resumes")).type());
        assertEquals(BrowserAgentCommandType.CHECK_AUTHORIZATION,
                command(service.checkAuthorization(profile)).type());

        assertConflict(() -> service.discoverResumes(profile));
        assertConflict(() -> service.close());
    }

    @Test
    void scanIsQueuedWithTheNormalizedLinkAndThePageLimitOfTheSearch() {
        searchIsReadyToScan();

        SearchProfile started = service.scanSearch(searchId);

        assertEquals(SearchStatus.SCANNING, started.search().status(),
                "сервис возвращает поиск в том состоянии, в которое его перевёл SearchService");
        BrowserAgentCommand queued = service.claimNext(AGENT_ID).orElseThrow();
        assertEquals(BrowserAgentCommandType.SCAN_SEARCH, queued.type());
        assertEquals(profileId, queued.payload().profileId());
        assertEquals(SCAN_URL, queued.payload().url());
        assertEquals(searchId, queued.payload().searchId());
        assertEquals(7, queued.payload().pagesLimit());
    }

    @Test
    void claimedScanAcceptsProgressOnlyFromItsAssignedAgent() {
        searchIsReadyToScan();
        service.scanSearch(searchId);
        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();

        service.updateScanProgress(claimed.id(), AGENT_ID, 2, 4, 200, 360);

        verify(searches).updateScanProgress(searchId, 2, 4, 200, 360);
        assertConflict(() -> service.updateScanProgress(claimed.id(), "another-agent", 3, 4, 300, 360));
        verify(searches, times(1)).updateScanProgress(any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void scanIsRejectedWhileApplicationsAreBusy() {
        when(applications.busy()).thenReturn(true);

        assertConflict(() -> service.scanSearch(searchId));

        verify(searches, never()).scan(any());
    }

    @Test
    void scanRequiresAReadyAgent() {
        agentReports(BrowserAgentRuntimeStatus.BUSY, true, profileId);

        assertConflict(() -> service.scanSearch(searchId));

        agentIsOffline();
        assertConflict(() -> service.scanSearch(searchId));
        verify(searches, never()).scan(any());
    }

    @Test
    void scanRequiresAnIdleCommandQueue() {
        service.open(profile);

        assertConflict(() -> service.scanSearch(searchId));

        verify(searches, never()).scan(any());
    }

    @Test
    void scanRequiresTheBrowserOfThatAccountToBeOpen() {
        when(searches.accountIdForSearch(searchId)).thenReturn(accountId);
        agentReports(BrowserAgentRuntimeStatus.READY, false, profileId);

        assertConflict(() -> service.scanSearch(searchId));

        agentReports(BrowserAgentRuntimeStatus.READY, true, otherProfileId);
        assertConflict(() -> service.scanSearch(searchId));
        verify(searches, never()).scan(any());
    }
}
