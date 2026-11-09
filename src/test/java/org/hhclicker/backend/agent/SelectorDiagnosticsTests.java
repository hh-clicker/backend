package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandType;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.browser.model.SelectorDiagnosticReport;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectorDiagnosticsTests extends BrowserAgentTestSupport {

    @Test
    void unobservedStateSurvivesTheDiagnosticResultWithoutBecomingAnError() {
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.VACANCY);
        service.claimNext(AGENT_ID).orElseThrow();
        var row = new SelectorDiagnosticReport.Row("vacancy", "archivedTitle", "Archive marker", "css=.archive",
                SelectorDiagnosticReport.Source.BUNDLED, SelectorDiagnosticReport.Result.STATE_NOT_CHECKED,
                false, 0, 0, "Current vacancy is not archived");
        var result = new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.VACANCY, START,
                SelectorDiagnosticReport.Status.CHECKED, "Current state checked", List.of(row),
                SelectorDiagnosticReport.Summary.PASSED, null, null, null);
        var completed = service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, result);
        assertEquals(SelectorDiagnosticReport.Result.STATE_NOT_CHECKED, completed.selectorDiagnostics().rows().getFirst().status());
        assertEquals(SelectorDiagnosticReport.Summary.PASSED, completed.selectorDiagnostics().summary());
    }

    @Test
    void repairMayReturnAFullContinuationOnlyWithTheRepairedPageAndTab() {
        var tab = java.util.UUID.randomUUID();
        var id = service.repairSelectors(profile, tab, "profile");
        service.claimNext(AGENT_ID).orElseThrow();
        var wrongTab = continuation(java.util.UUID.randomUUID().toString());
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, wrongTab));
        var full = continuation(tab.toString());
        assertEquals(full, service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, full).selectorDiagnostics());
    }

    private SelectorDiagnosticReport continuation(String tabId) {
        var fixed = new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.PROFILE, START,
                SelectorDiagnosticReport.Status.CHECKED, "Repaired", List.of(), SelectorDiagnosticReport.Summary.PASSED,
                null, null, tabId);
        return new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.FULL, START,
                SelectorDiagnosticReport.Status.CHECKED, "Continued", List.of(), SelectorDiagnosticReport.Summary.PASSED,
                List.of(fixed, report(SelectorDiagnosticReport.PageType.RESUME), report(SelectorDiagnosticReport.PageType.SEARCH),
                        report(SelectorDiagnosticReport.PageType.VACANCY)), null, null);
    }

    @Test
    void repairIsAnExplicitReadOnlyCommandAndUsesTheExistingBusyGuard() {
        var tab = java.util.UUID.randomUUID();
        var id = service.repairSelectors(profile, tab, "profile");
        var command = service.findById(id);
        assertEquals(BrowserAgentCommandType.DIAGNOSE_SELECTORS, command.type());
        assertEquals("profile", command.payload().diagnosticTarget().repairGroup());
        assertEquals(tab.toString(), command.payload().diagnosticTarget().tabId());
        assertNull(command.payload().application());
        assertNull(command.payload().url());
        assertConflict(() -> service.repairSelectors(profile, tab, "search"));
        verifyNoInteractions(authorizationService, vacancySelection);
    }

    @Test
    void actionGroupsAndMissingTabCannotBeRepaired() {
        assertInvalidAgentData(() -> service.repairSelectors(profile, java.util.UUID.randomUUID(), "letterForm"));
        assertInvalidAgentData(() -> service.repairSelectors(profile, null, "profile"));
    }

    @Test
    void dependentBlockedStatusIsPreservedInFullDiagnosticResults() {
        when(diagnosticResumes.findAll(accountId)).thenReturn(List.of());
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.FULL, null);
        service.claimNext(AGENT_ID).orElseThrow();
        var blocked = new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.RESUME, START,
                SelectorDiagnosticReport.Status.BLOCKED, "Blocked by profile.resumeLink", List.of(),
                SelectorDiagnosticReport.Summary.FAILED, null, null, null);
        var full = new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.FULL, START,
                SelectorDiagnosticReport.Status.CHECKED, "Failed dependency", List.of(), SelectorDiagnosticReport.Summary.FAILED,
                List.of(report(SelectorDiagnosticReport.PageType.PROFILE), blocked,
                        report(SelectorDiagnosticReport.PageType.SEARCH), report(SelectorDiagnosticReport.PageType.VACANCY)), null, null);
        var completed = service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, full);
        assertEquals(SelectorDiagnosticReport.Status.BLOCKED, completed.selectorDiagnostics().scenarios().get(1).status());
    }

    @Test
    void currentDiagnosticsAcceptsTabSelectionAndForwardsTheChosenHandle() {
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.CURRENT);
        service.claimNext(AGENT_ID).orElseThrow();
        var tabId = java.util.UUID.randomUUID();
        var selection = new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.UNKNOWN, START,
                SelectorDiagnosticReport.Status.SELECT_TAB, "Choose tab", List.of(), null, null, null, null,
                List.of(new SelectorDiagnosticReport.Tab(tabId.toString(), "1. Profile")));
        assertEquals(selection, service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, selection).selectorDiagnostics());
        var selectedId = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.CURRENT, null, tabId);
        assertEquals(tabId.toString(), service.findById(selectedId).payload().diagnosticTarget().tabId());
        assertInvalidAgentData(() -> service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.FULL, null, tabId));
    }

    @Test
    void fullDiagnosticsUsesSelectedResumeAndItsSavedSearchWithoutStartingAScan() {
        var resume = new org.hhclicker.backend.resume.model.HhResume(resumeId, accountId, HH_RESUME_ID, "Role", null, START, START);
        when(diagnosticResumes.findById(resumeId)).thenReturn(new org.hhclicker.backend.resume.service.ResumeProfile(resume, "Account", null));
        var search = new org.hhclicker.backend.search.model.VacancySearch(searchId, resumeId, "Saved search",
                "https://hh.ru/search/vacancy?text=java", "hash", 1, org.hhclicker.backend.search.model.SearchStatus.ACTIVE,
                0, 0, null, null, START, START);
        when(searches.findAll(resumeId)).thenReturn(List.of(new org.hhclicker.backend.search.service.SearchProfile(search, "Role")));
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.FULL, resumeId);
        var target = service.findById(id).payload().diagnosticTarget();
        assertEquals(HH_RESUME_ID, target.hhResumeId());
        assertEquals(search.sourceUrl(), target.searchUrl());
        assertEquals("Saved search", target.searchName());
        verify(searches, never()).scan(any());
        verifyNoInteractions(authorizationService, vacancySelection);
    }

    @Test
    void fullDiagnosticsRejectsAResumeFromAnotherAccount() {
        var resume = new org.hhclicker.backend.resume.model.HhResume(resumeId, java.util.UUID.randomUUID(), HH_RESUME_ID, "Role", null, START, START);
        when(diagnosticResumes.findById(resumeId)).thenReturn(new org.hhclicker.backend.resume.service.ResumeProfile(resume, "Other", null));
        assertInvalidAgentData(() -> service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.FULL, resumeId));
        verifyNoInteractions(searches);
    }

    @Test
    void currentDiagnosticsAcceptsAnAutoDetectedPageTypeButNotAFullReport() {
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.CURRENT, null);
        service.claimNext(AGENT_ID).orElseThrow();
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, report(SelectorDiagnosticReport.PageType.FULL)));
        var completed = service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, report(SelectorDiagnosticReport.PageType.SEARCH));
        assertEquals(SelectorDiagnosticReport.PageType.SEARCH, completed.selectorDiagnostics().pageType());
    }

    @Test
    void fullReportRequiresExactlyTheRegisteredOrderedScenarios() {
        when(diagnosticResumes.findAll(accountId)).thenReturn(List.of());
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.FULL, null);
        service.claimNext(AGENT_ID).orElseThrow();
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, report(SelectorDiagnosticReport.PageType.FULL)));
        var full = new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.FULL, START,
                SelectorDiagnosticReport.Status.CHECKED, "Checked", List.of(), SelectorDiagnosticReport.Summary.INCOMPLETE,
                List.of(report(SelectorDiagnosticReport.PageType.PROFILE), report(SelectorDiagnosticReport.PageType.RESUME),
                        report(SelectorDiagnosticReport.PageType.SEARCH), report(SelectorDiagnosticReport.PageType.VACANCY)), null, null);
        assertEquals(full, service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, full).selectorDiagnostics());
    }
    private SelectorDiagnosticReport report(SelectorDiagnosticReport.PageType type) {
        return new SelectorDiagnosticReport(type, START, SelectorDiagnosticReport.Status.CHECKED, "Checked", List.of());
    }

    @Test
    void reportIsEphemeralIdempotentAndDoesNotModifyAuthorizationOrVacancies() {
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.PROFILE);
        var queued = service.findById(id);
        assertEquals(BrowserAgentCommandType.DIAGNOSE_SELECTORS, queued.type());
        assertEquals(profileId, queued.payload().profileId());
        assertNull(queued.payload().url());
        assertNull(queued.payload().application());
        service.claimNext(AGENT_ID).orElseThrow();
        var report = report(SelectorDiagnosticReport.PageType.PROFILE);
        var completed = service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, report);
        assertEquals(BrowserAgentCommandStatus.SUCCEEDED, completed.status());
        assertEquals(report, completed.selectorDiagnostics());
        assertEquals(completed, service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, report));
        verifyNoInteractions(authorizationService, vacancySelection);
    }

    @Test
    void missingOrWrongReportIsRejectedAndAnotherAgentCannotCompleteTheCommand() {
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.SEARCH);
        service.claimNext(AGENT_ID).orElseThrow();
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null));
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, report(SelectorDiagnosticReport.PageType.PROFILE)));
        assertConflict(() -> service.complete(id, "other-agent", BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, report(SelectorDiagnosticReport.PageType.SEARCH)));
    }

    @Test
    void diagnosticsCannotInterruptPendingCommands() {
        service.open(profile);
        assertConflict(() -> service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.PROFILE));
    }

    @Test
    void diagnosticsCannotInterruptAnApplicationRun() {
        when(applications.busy()).thenReturn(true);
        assertConflict(() -> service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.PROFILE));
    }

    @Test
    void pausedRunAllowsDiagnosticsOnlyForItsOwnInspectableProfile() {
        when(applications.busy()).thenReturn(true);
        when(applications.inspectionAllowed(profileId)).thenReturn(true);
        var id = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.VACANCY);
        assertEquals(BrowserAgentCommandType.DIAGNOSE_SELECTORS, service.findById(id).type());
    }

    @Test
    void diagnosticsIsUnavailableWhileAgentIsBusy() {
        agentReports(BrowserAgentRuntimeStatus.BUSY, true, profileId);
        assertConflict(() -> service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.PROFILE));
    }

    @Test
    void reportCannotBeAttachedToAnOrdinaryCommand() {
        var id = service.open(profile);
        service.claimNext(AGENT_ID).orElseThrow();
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, authorized(), null, null, null, null, null, report(SelectorDiagnosticReport.PageType.PROFILE)));
    }
}
