package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandType;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserFocusTests extends BrowserAgentTestSupport {
    @Test
    void focusingDuringAnApplicationRunOnlyQueuesAReadOnlyTabAction() {
        when(applications.busy()).thenReturn(true);
        agentReports(BrowserAgentRuntimeStatus.BUSY, true, profileId);
        var id = service.focusVacancy(profile, "123");
        var command = service.findById(id);
        assertEquals(BrowserAgentCommandType.FOCUS_VACANCY, command.type());
        assertEquals("https://hh.ru/vacancy/123", command.payload().url());
        assertNull(command.payload().application());
        assertConflict(() -> service.openUrl(profile, "https://hh.ru/vacancy/123"));
    }

    @Test
    void focusNeverOpensOrSwitchesAProfile() {
        agentReports(BrowserAgentRuntimeStatus.READY, false, null);
        assertConflict(() -> service.focusVacancy(profile, "123"));
        agentReports(BrowserAgentRuntimeStatus.READY, true, otherProfileId);
        assertConflict(() -> service.focusVacancy(profile, "123"));
        assertInvalidAgentData(() -> service.focusVacancy(profile, "123/../456"));
    }

    @Test
    void urlOpeningCanLaunchAClosedBrowserWithoutRequiringAnAuthorizationResult() {
        agentReports(BrowserAgentRuntimeStatus.READY, false, null);
        var id = service.openUrl(profile, "https://hh.ru/vacancy/123");
        service.claimNext(AGENT_ID).orElseThrow();
        service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null);
        verify(statusService).updateBrowserState(true, profileId);
        verifyNoInteractions(authorizationService);
    }

    @Test
    void switchingProfilesIsBlockedWhileAnotherOperationIsBusy() {
        agentReports(BrowserAgentRuntimeStatus.BUSY, true, otherProfileId);
        assertConflict(() -> service.openUrl(profile, "https://hh.ru/vacancy/123"));
    }
}
