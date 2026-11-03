package org.hhclicker.backend.agent;

import org.junit.jupiter.api.BeforeEach;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandConflictException;
import org.hhclicker.backend.agent.exception.InvalidBrowserAgentDataException;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.agent.model.BrowserAgentSnapshot;
import org.hhclicker.backend.agent.service.BrowserAgentCommandService;
import org.hhclicker.backend.agent.service.BrowserAgentStatusService;
import org.hhclicker.backend.application.model.ApplicationModels.Task;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.browser.model.BrowserAuthorizationResult;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.model.BrowserResumeSummary;
import org.hhclicker.backend.browser.service.BrowserAuthorizationService;
import org.hhclicker.backend.browser.service.BrowserProfileLookup;
import org.hhclicker.backend.search.service.SearchService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.support.MutableClock;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая обвязка unit-тестов транспортного адаптера Browser Agent.
 * <p>
 * Все соседние модули — моки: пакет {@code agent} только маршрутизирует результаты и владеет
 * очередью команд в памяти. Время двигается вручную через {@link MutableClock}.
 */
abstract class BrowserAgentTestSupport {

    protected static final Instant START = Instant.parse("2026-09-04T09:00:00Z");
    protected static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    protected static final String AGENT_ID = "local-browser-agent";
    protected static final String OTHER_AGENT_ID = "second-browser-agent";
    protected static final String HH_RESUME_ID = "abcdef0123456789abcd";

    protected final MutableClock clock = new MutableClock(START);
    protected final BrowserAgentStatusService statusService = mock(BrowserAgentStatusService.class);
    protected final BrowserAuthorizationService authorizationService = mock(BrowserAuthorizationService.class);
    protected final SearchService searches = mock(SearchService.class);
    protected final BrowserProfileLookup profiles = mock(BrowserProfileLookup.class);
    protected final ApplicationService applications = mock(ApplicationService.class);
    protected final VacancySelectionService vacancySelection = mock(VacancySelectionService.class);

    protected final BrowserAgentCommandService service = new BrowserAgentCommandService(
            clock, COMMAND_TIMEOUT, statusService, authorizationService, searches, profiles, applications,
            vacancySelection);

    protected final UUID profileId = UUID.randomUUID();
    protected final UUID otherProfileId = UUID.randomUUID();
    protected final UUID accountId = UUID.randomUUID();
    protected final UUID resumeId = UUID.randomUUID();
    protected final UUID searchId = UUID.randomUUID();
    protected final UUID itemId = UUID.randomUUID();
    protected final UUID vacancyId = UUID.randomUUID();

    protected final BrowserProfile profile = new BrowserProfile(profileId, accountId, START, START);

    @BeforeEach
    void agentIsReadyWithAnOpenBrowser() {
        agentReports(BrowserAgentRuntimeStatus.READY, true, profileId);
        when(profiles.forAccount(accountId)).thenReturn(profile);
    }

    /**
     * Подменяет снимок статуса, который сервис читает через {@code BrowserAgentStatusService}.
     */
    protected void agentReports(BrowserAgentRuntimeStatus status, boolean browserOpen, UUID activeProfileId) {
        agentReports(AGENT_ID, status, browserOpen, activeProfileId);
    }

    protected void agentReports(String agentId, BrowserAgentRuntimeStatus status, boolean browserOpen,
                                UUID activeProfileId) {
        when(statusService.getStatus()).thenReturn(new BrowserAgentSnapshot(agentId, "1.0.0", status,
                clock.instant(), 15, browserOpen, activeProfileId));
    }

    protected void agentIsOffline() {
        when(statusService.getStatus()).thenReturn(BrowserAgentSnapshot.offline(15));
    }

    protected BrowserAuthorizationResult authorized() {
        return new BrowserAuthorizationResult(AuthorizationStatus.AUTHORIZED, null);
    }

    protected BrowserAuthorizationResult authorization(AuthorizationStatus status, String message) {
        return new BrowserAuthorizationResult(status, message);
    }

    protected BrowserResumeSummary resume(String hhResumeId, String plainText) {
        return new BrowserResumeSummary(hhResumeId, "Java-разработчик", plainText);
    }

    protected Task task() {
        return new Task(itemId, profileId, HH_RESUME_ID, "118123456",
                "https://hh.ru/search/vacancy?page=0", 0, "Обычное письмо");
    }

    protected void questionnaireVacancyIsReady() {
        Vacancy vacancy = mock(Vacancy.class);
        when(vacancy.id()).thenReturn(vacancyId);
        when(vacancy.resumeId()).thenReturn(resumeId);
        when(vacancy.hhVacancyId()).thenReturn("118123456");
        when(vacancySelection.questionnaireVerificationTarget(vacancyId)).thenReturn(vacancy);
        when(applications.accountForResume(resumeId)).thenReturn(accountId);
    }

    protected void assertConflict(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(BrowserAgentCommandConflictException.class, executable);
        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("BROWSER_AGENT_COMMAND_CONFLICT", exception.machineCode());
    }

    protected void assertInvalidAgentData(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidBrowserAgentDataException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_BROWSER_AGENT_DATA", exception.machineCode());
    }
}
