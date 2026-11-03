package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Выдача следующей команды агенту: владение, порядок и подтягивание очереди откликов.
 */
class BrowserAgentCommandClaimTests extends BrowserAgentTestSupport {

    @Test
    void nothingIsHandedOutWhenThereIsNoWork() {
        assertTrue(service.claimNext(AGENT_ID).isEmpty());
    }

    @Test
    void queuedCommandIsHandedOutAndBecomesInProgress() {
        service.open(profile);
        clock.advanceSeconds(5);

        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();

        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, claimed.status());
        assertEquals(AGENT_ID, claimed.agentId());
        assertEquals(START, claimed.createdAt());
        assertEquals(START.plusSeconds(5), claimed.startedAt());
        assertEquals(claimed.status(), service.findById(claimed.id()).status(),
                "выданный snapshot совпадает с сохранённым состоянием");
    }

    @Test
    void agentIdentifierIsTrimmedBeforeItIsStored() {
        service.open(profile);

        assertEquals(AGENT_ID, service.claimNext("  " + AGENT_ID + "  ").orElseThrow().agentId());
    }

    @Test
    void commandsThatCheckTheSessionResetAuthorizationToUnknownWhenHandedOut() {
        service.checkAuthorization(profile);

        service.claimNext(AGENT_ID);

        verify(authorizationService).record(profileId, AuthorizationStatus.UNKNOWN);
    }

    @Test
    void commandsThatDoNotTouchTheSessionLeaveAuthorizationAlone() {
        service.openUrl(profile, "https://hh.ru/applicant/resumes");

        service.claimNext(AGENT_ID);

        verify(authorizationService, never()).record(any(), any());
    }

    @Test
    void onlyOneCommandIsInFlightAtATime() {
        service.open(profile);
        service.openUrl(profile, "https://hh.ru/applicant/resumes");

        assertTrue(service.claimNext(AGENT_ID).isPresent());

        assertTrue(service.claimNext(AGENT_ID).isEmpty(), "тот же агент не получает вторую команду");
        assertTrue(service.claimNext(OTHER_AGENT_ID).isEmpty(), "чужой агент не перехватывает работу");
    }

    @Test
    void commandsAreHandedOutInTheOrderTheyWereQueued() {
        service.open(profile);
        service.openUrl(profile, "https://hh.ru/applicant/resumes");

        BrowserAgentCommand first = service.claimNext(AGENT_ID).orElseThrow();
        assertEquals(BrowserAgentCommandType.OPEN_BROWSER, first.type());

        service.complete(first.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                authorized(), null, null, null, null, null);

        assertEquals(BrowserAgentCommandType.OPEN_URL, service.claimNext(AGENT_ID).orElseThrow().type());
    }

    @Test
    void finishedCommandIsNeverHandedOutAgain() {
        service.openUrl(profile, "https://hh.ru/applicant/resumes");
        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();
        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, null, null, null);

        assertTrue(service.claimNext(AGENT_ID).isEmpty());
    }

    @Test
    void applicationQueueIsPolledOnlyWhenNoBrowserCommandIsPending() {
        when(applications.claim(profileId)).thenReturn(Optional.of(task()));

        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();

        assertEquals(BrowserAgentCommandType.APPLY_VACANCY, claimed.type());
        assertEquals(profileId, claimed.payload().profileId());
        assertSame(itemId, claimed.payload().application().itemId());
        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, claimed.status());
    }

    @Test
    void browserCommandIsAlwaysServedBeforeTheApplicationQueue() {
        when(applications.claim(profileId)).thenReturn(Optional.of(task()));
        service.open(profile);

        assertEquals(BrowserAgentCommandType.OPEN_BROWSER, service.claimNext(AGENT_ID).orElseThrow().type());

        verify(applications, never()).claim(any());
    }

    @Test
    void applicationQueueIsNotPolledByAnAgentThatIsNotTheReportedOne() {
        when(applications.claim(profileId)).thenReturn(Optional.of(task()));

        assertTrue(service.claimNext(OTHER_AGENT_ID).isEmpty());

        verify(applications, never()).claim(any());
    }

    @Test
    void applicationQueueIsNotPolledWithoutAnOpenBrowser() {
        agentReports(org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus.READY, false, profileId);
        when(applications.claim(profileId)).thenReturn(Optional.of(task()));

        assertTrue(service.claimNext(AGENT_ID).isEmpty());

        verify(applications, never()).claim(any());
    }

    @Test
    void emptyApplicationQueueSimplyLeavesTheAgentWithoutWork() {
        when(applications.claim(profileId)).thenReturn(Optional.empty());

        assertTrue(service.claimNext(AGENT_ID).isEmpty());

        verify(applications).claim(profileId);
    }
}
