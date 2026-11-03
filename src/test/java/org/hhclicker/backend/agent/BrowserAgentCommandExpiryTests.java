package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandType;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.PreparedSearchScan;
import org.hhclicker.backend.search.service.SearchProfile;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Истечение команд: границы таймаутов и восстановление состояния соседних модулей.
 */
class BrowserAgentCommandExpiryTests extends BrowserAgentTestSupport {

    private static final String TIMEOUT_MESSAGE = "Browser Agent не подтвердил выполнение команды вовремя.";
    private static final String SCAN_URL =
            "https://hh.ru/search/vacancy?items_on_page=100&order_by=publication_time&page=0&text=java";

    private BrowserAgentCommand claim(BrowserAgentCommandType type) {
        switch (type) {
            case OPEN_BROWSER -> service.open(profile);
            case OPEN_URL -> service.openUrl(profile, "https://hh.ru/applicant/resumes");
            case CHECK_AUTHORIZATION -> service.checkAuthorization(profile);
            case CLOSE_BROWSER -> service.close();
            case DISCOVER_RESUMES -> service.discoverResumes(profile);
            case SCAN_SEARCH -> startScan();
            case INSPECT_APPLICATION -> {
                questionnaireVacancyIsReady();
                service.verifyQuestionnaireApplication(vacancyId);
            }
            case APPLY_VACANCY -> when(applications.claim(profileId)).thenReturn(Optional.of(task()));
        }
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private void startScan() {
        VacancySearch search = new VacancySearch(searchId, resumeId, "Java в Москве", SCAN_URL, "a".repeat(64),
                1, SearchStatus.SCANNING, 0, 0, null, null, START, START);
        when(searches.accountIdForSearch(searchId)).thenReturn(accountId);
        when(searches.scan(searchId)).thenReturn(
                new PreparedSearchScan(new SearchProfile(search, "Java-разработчик"), SCAN_URL));
        service.scanSearch(searchId);
    }

    @Test
    void freshQueuedCommandSurvivesTheSweep() {
        UUID id = service.open(profile);
        clock.advanceSeconds(59);

        service.expireCommands();

        assertEquals(BrowserAgentCommandStatus.QUEUED, service.findById(id).status());
    }

    @Test
    void queuedCommandExpiresExactlyAfterAMinute() {
        UUID id = service.open(profile);
        clock.advanceSeconds(60);

        service.expireCommands();

        BrowserAgentCommand expired = service.findById(id);
        assertEquals(BrowserAgentCommandStatus.FAILED, expired.status());
        assertEquals(TIMEOUT_MESSAGE, expired.errorMessage());
        assertEquals(START.plusSeconds(60), expired.completedAt());
        assertTrue(service.claimNext(AGENT_ID).isEmpty(), "истёкшая команда больше не выдаётся агенту");
    }

    @ParameterizedTest(name = "{0} истекает через {1} с")
    @CsvSource({
            "OPEN_URL, 30",
            "CLOSE_BROWSER, 30",
            "OPEN_BROWSER, 60",
            "CHECK_AUTHORIZATION, 60",
            "DISCOVER_RESUMES, 90",
            "INSPECT_APPLICATION, 60",
            "APPLY_VACANCY, 480",
            "SCAN_SEARCH, 2400"
    })
    void runningCommandExpiresOnlyAfterItsOwnTimeout(BrowserAgentCommandType type, long timeoutSeconds) {
        BrowserAgentCommand claimed = claim(type);

        clock.advanceSeconds(timeoutSeconds - 1);
        service.expireCommands();
        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(claimed.id()).status());

        clock.advanceSeconds(1);
        service.expireCommands();
        assertEquals(BrowserAgentCommandStatus.FAILED, service.findById(claimed.id()).status());
    }

    @Test
    void expiredApplicationCommandReportsTheAttemptAsLost() {
        BrowserAgentCommand claimed = claim(BrowserAgentCommandType.APPLY_VACANCY);
        clock.advanceSeconds(480);

        service.expireCommands();

        verify(applications).lost(itemId);
        assertEquals(BrowserAgentCommandStatus.FAILED, service.findById(claimed.id()).status());
    }

    @Test
    void expiredScanIsReportedToTheSearchModule() {
        claim(BrowserAgentCommandType.SCAN_SEARCH);
        clock.advanceSeconds(2400);

        service.expireCommands();

        verify(searches).failScan(searchId,
                "Агент не завершил сбор вовремя. Проверьте браузер и повторите сканирование.");
    }

    @Test
    void finishedCommandsAreNeverTouchedBySweeps() {
        BrowserAgentCommand claimed = claim(BrowserAgentCommandType.OPEN_URL);
        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, null, null, null);

        clock.advanceSeconds(100_000);
        service.expireCommands();

        BrowserAgentCommand stored = service.findById(claimed.id());
        assertEquals(BrowserAgentCommandStatus.SUCCEEDED, stored.status());
        assertEquals(claimed.startedAt(), stored.startedAt());
    }

    @Test
    void repeatedSweepDoesNotReportTheSameLossTwice() {
        claim(BrowserAgentCommandType.APPLY_VACANCY);
        clock.advanceSeconds(480);

        service.expireCommands();
        service.expireCommands();
        service.expireCommands();

        verify(applications, times(1)).lost(itemId);
    }

    @Test
    void expiryOfARunningCommandFreesTheAgentForTheNextOne() {
        BrowserAgentCommand first = claim(BrowserAgentCommandType.OPEN_URL);
        UUID second = service.openUrl(profile, "https://hh.ru/search/vacancy");

        clock.advanceSeconds(30);
        service.expireCommands();

        assertEquals(BrowserAgentCommandStatus.FAILED, service.findById(first.id()).status());
        assertEquals(second, service.claimNext(AGENT_ID).orElseThrow().id(),
                "освободившийся агент получает следующую команду очереди");
    }

    @Test
    void sweepDoesNotTouchNeighboursWhenNothingExpired() {
        claim(BrowserAgentCommandType.APPLY_VACANCY);
        clock.advanceSeconds(60);

        service.expireCommands();

        verify(applications, never()).lost(any());
        verify(searches, never()).failScan(any(), anyString());
    }
}
