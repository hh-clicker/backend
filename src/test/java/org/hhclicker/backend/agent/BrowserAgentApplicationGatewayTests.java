package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandNotFoundException;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.application.model.ApplicationModels.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Реализация ApplicationRunGateway: запуск откликов и управление шагом через команду агента.
 */
class BrowserAgentApplicationGatewayTests extends BrowserAgentTestSupport {

    private final UUID runId = UUID.randomUUID();
    private final UUID vacancyId = UUID.randomUUID();

    private Start start() {
        return new Start(runId, resumeId, List.of(vacancyId), 20, 10, 10, "Обычное письмо", true);
    }

    private Run run(RunStatus status) {
        Item item = new Item(itemId, runId, vacancyId, "118123456", "Java-разработчик", "Синтетическая компания",
                "QUEUED", null, null, null, false, "OPEN");
        return new Run(runId, 1, accountId, resumeId, profileId, HH_RESUME_ID, "Java-разработчик", status,
                START, null, 20, 20, 10, 10, "Обычное письмо", true, List.of(item));
    }

    private BrowserAgentCommand claimedApply() {
        when(applications.claim(profileId)).thenReturn(Optional.of(task()));
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    @Test
    void alreadyAcceptedRequestStaysRetrievableEvenWithoutAnOpenBrowser() {
        when(applications.exists(runId)).thenReturn(true);
        when(applications.start(any())).thenReturn(run(RunStatus.RUNNING));
        agentIsOffline();

        assertEquals(runId, service.startApplications(start()).id());

        verify(applications).start(any());
        verifyNoInteractions(profiles);
    }

    @Test
    void newRunRequiresTheReadyBrowserOfThatAccount() {
        when(applications.accountForResume(resumeId)).thenReturn(accountId);
        when(applications.start(any())).thenReturn(run(RunStatus.RUNNING));

        service.startApplications(start());

        verify(profiles).forAccount(accountId);
        verify(applications).start(any());
    }

    @Test
    void newRunIsRefusedWhileTheAgentIsNotReady() {
        when(applications.accountForResume(resumeId)).thenReturn(accountId);
        agentReports(BrowserAgentRuntimeStatus.BUSY, true, profileId);

        assertConflict(() -> service.startApplications(start()));

        verify(applications, never()).start(any());
    }

    @Test
    void newRunIsRefusedWithoutTheBrowserOfThatProfile() {
        when(applications.accountForResume(resumeId)).thenReturn(accountId);
        agentReports(BrowserAgentRuntimeStatus.READY, false, profileId);
        assertConflict(() -> service.startApplications(start()));

        agentReports(BrowserAgentRuntimeStatus.READY, true, otherProfileId);
        assertConflict(() -> service.startApplications(start()));

        verify(applications, never()).start(any());
    }

    @Test
    void newRunIsRefusedWhileABrowserCommandIsStillPending() {
        when(applications.accountForResume(resumeId)).thenReturn(accountId);
        service.open(profile);

        assertConflict(() -> service.startApplications(start()));

        verify(applications, never()).start(any());
    }

    @Test
    void resumingARunRequiresTheBrowserOfItsProfile() {
        when(applications.find(runId)).thenReturn(run(RunStatus.PAUSED_USER));
        agentReports(BrowserAgentRuntimeStatus.READY, true, otherProfileId);

        assertConflict(() -> service.changeApplicationStatus(runId, RunStatus.RUNNING));

        verify(applications, never()).changeStatus(any(), any());
    }

    @Test
    void resumingARunWithTheRightBrowserIsDelegated() {
        when(applications.find(runId)).thenReturn(run(RunStatus.PAUSED_USER));
        when(applications.changeStatus(runId, RunStatus.RUNNING)).thenReturn(run(RunStatus.RUNNING));

        service.changeApplicationStatus(runId, RunStatus.RUNNING);

        verify(applications).changeStatus(runId, RunStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"PAUSED_USER", "CANCELLED"})
    void pausingAndCancellingNeverDependOnTheBrowser(RunStatus status) {
        agentIsOffline();
        when(applications.changeStatus(runId, status)).thenReturn(run(status));

        service.changeApplicationStatus(runId, status);

        verify(applications).changeStatus(runId, status);
        verify(applications, never()).find(any());
    }

    @Test
    void submissionPermitIsDelegatedToTheApplicationModule() {
        BrowserAgentCommand claimed = claimedApply();
        when(applications.permit(itemId)).thenReturn(true);

        assertTrue(service.applicationControl(claimed.id(), AGENT_ID, true));

        verify(applications).permit(itemId);
        verify(applications, never()).allowed(any());
    }

    @Test
    void continuationCheckAsksWhetherTheStepIsStillAllowed() {
        BrowserAgentCommand claimed = claimedApply();
        when(applications.allowed(itemId)).thenReturn(true);

        assertTrue(service.applicationControl(claimed.id(), AGENT_ID, false));

        verify(applications).allowed(itemId);
        verify(applications, never()).permit(any());
    }

    @Test
    void secondPermitIsForwardedAgainAndTheApplicationModuleDecides() {
        BrowserAgentCommand claimed = claimedApply();
        when(applications.permit(itemId)).thenReturn(true, false);

        assertTrue(service.applicationControl(claimed.id(), AGENT_ID, true));
        assertFalse(service.applicationControl(claimed.id(), AGENT_ID, true),
                "адаптер не хранит собственное состояние разрешения");

        verify(applications, times(2)).permit(itemId);
    }

    @Test
    void controlOfACommandOwnedByAnotherAgentIsSilentlyRefused() {
        BrowserAgentCommand claimed = claimedApply();

        assertFalse(service.applicationControl(claimed.id(), OTHER_AGENT_ID, true));

        verify(applications, never()).permit(any());
    }

    @Test
    void controlOfACommandThatIsNotAnApplicationIsSilentlyRefused() {
        service.openUrl(profile, "https://hh.ru/applicant/resumes");
        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();

        assertFalse(service.applicationControl(claimed.id(), AGENT_ID, true));
        assertFalse(service.prepareApplicationLetter(claimed.id(), AGENT_ID,
                new PreparedLetter("Обычное письмо", "STATIC", null, null)));

        verify(applications, never()).permit(any());
        verify(applications, never()).prepareLetter(any(), any());
    }

    @Test
    void controlOfAFinishedCommandIsSilentlyRefused() {
        BrowserAgentCommand claimed = claimedApply();
        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.FAILED,
                "Chrome закрылся", null, null, null, null, null, null);

        assertFalse(service.applicationControl(claimed.id(), AGENT_ID, true));
        assertFalse(service.prepareApplicationLetter(claimed.id(), AGENT_ID,
                new PreparedLetter("Обычное письмо", "STATIC", null, null)));

        verify(applications, never()).permit(any());
        verify(applications, never()).prepareLetter(any(), any());
    }

    @Test
    void controlOfAnUnknownCommandIsReportedAsNotFound() {
        assertThrows(BrowserAgentCommandNotFoundException.class,
                () -> service.applicationControl(UUID.randomUUID(), AGENT_ID, true));
        assertThrows(BrowserAgentCommandNotFoundException.class,
                () -> service.prepareApplicationLetter(UUID.randomUUID(), AGENT_ID,
                        new PreparedLetter("Обычное письмо", "STATIC", null, null)));
    }

    @Test
    void preparedLetterIsForwardedWithTheItemOfThatCommand() {
        BrowserAgentCommand claimed = claimedApply();
        PreparedLetter letter = new PreparedLetter("Обычное письмо", "FALLBACK", "GENERATION_TIMEOUT", null);
        when(applications.prepareLetter(itemId, letter)).thenReturn(true);

        assertTrue(service.prepareApplicationLetter(claimed.id(), AGENT_ID, letter));

        verify(applications).prepareLetter(itemId, letter);
    }

    @Test
    void letterOfAnotherAgentIsSilentlyRefused() {
        BrowserAgentCommand claimed = claimedApply();

        assertFalse(service.prepareApplicationLetter(claimed.id(), OTHER_AGENT_ID,
                new PreparedLetter("Обычное письмо", "STATIC", null, null)));

        verify(applications, never()).prepareLetter(any(), any());
    }

    @Test
    void failureOfOneApplicationDoesNotReopenAnAlreadyFinishedCommand() {
        BrowserAgentCommand first = claimedApply();
        service.complete(first.id(), AGENT_ID, BrowserAgentCommandResultStatus.FAILED,
                "Chrome закрылся", null, null, null, null, null, null);

        BrowserAgentCommand second = service.claimNext(AGENT_ID).orElseThrow();
        when(applications.allowed(itemId)).thenReturn(true);

        assertFalse(service.applicationControl(first.id(), AGENT_ID, true),
                "завершённая команда больше не управляет шагом");
        assertTrue(service.applicationControl(second.id(), AGENT_ID, false),
                "новая команда обслуживается независимо от неудачи предыдущей");
        verify(applications, times(1)).lost(itemId);
    }
}
