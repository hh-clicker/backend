package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.model.SelectorDiagnosticReport;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeSelectorIssuesTests extends BrowserAgentTestSupport {
    private SelectorDiagnosticReport broken(SelectorDiagnosticReport.PageType type) {
        return new SelectorDiagnosticReport(type, START, SelectorDiagnosticReport.Status.CHECKED,
                "Не прочитан раздел резюме", List.of(new SelectorDiagnosticReport.Row("profile", "root", "Раздел резюме",
                "css=main", SelectorDiagnosticReport.Source.BUNDLED, SelectorDiagnosticReport.Result.FAIL,
                true, 0, 0, "Не найден обязательный элемент")), SelectorDiagnosticReport.Summary.FAILED,
                null, null, UUID.randomUUID().toString(), null, List.of(), null);
    }

    @Test
    void failedRuntimeCommandRetainsOnlyItsOwnAccountContextAndNewSuccessClearsIt() {
        var id = service.checkAuthorization(profile);
        service.claimNext(AGENT_ID).orElseThrow();
        var issue = broken(SelectorDiagnosticReport.PageType.PROFILE);
        service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.FAILED, "Не прочитан профиль",
                null, null, null, null, null, null, issue);
        assertEquals(issue, service.runtimeSelectorIssues(accountId).getFirst().report());
        assertEquals(id, service.runtimeSelectorIssues(accountId).getFirst().commandId());
        var anotherAccount = UUID.randomUUID();
        when(profiles.forAccount(anotherAccount)).thenReturn(new BrowserProfile(otherProfileId, anotherAccount, START, START));
        assertTrue(service.runtimeSelectorIssues(anotherAccount).isEmpty());

        var next = service.checkAuthorization(profile);
        assertTrue(service.runtimeSelectorIssues(accountId).isEmpty(), "New queued attempt hides the previous issue");
        service.claimNext(AGENT_ID).orElseThrow();
        assertTrue(service.runtimeSelectorIssues(accountId).isEmpty(), "In-progress attempts must not expose old errors");
        service.complete(next, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, authorized(), null, null, null, null, null);
        assertTrue(service.runtimeSelectorIssues(accountId).isEmpty());
        verifyNoInteractions(vacancySelection);
    }

    @Test
    void onlyRelevantVerifiedFieldsResolveThePreviousIssue() {
        var id = service.checkAuthorization(profile);
        service.claimNext(AGENT_ID).orElseThrow();
        var issue = broken(SelectorDiagnosticReport.PageType.PROFILE);
        service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.FAILED, "Не прочитан профиль",
                null, null, null, null, null, null, issue);
        var old = issue.rows().getFirst();
        var passed = new SelectorDiagnosticReport.Row(old.group(), old.key(), old.purpose(), old.selector(), old.source(),
                SelectorDiagnosticReport.Result.PASS, old.required(), 1, 1, "Проверено");
        var anotherTab = UUID.randomUUID();
        var verified = new SelectorDiagnosticReport(issue.pageType(), START, SelectorDiagnosticReport.Status.CHECKED,
                "Проверено", List.of(passed), SelectorDiagnosticReport.Summary.PASSED, null, null, anotherTab.toString(), null, List.of(), null);

        var unrelated = service.diagnoseSelectors(profile, SelectorDiagnosticReport.PageType.PROFILE);
        service.claimNext(AGENT_ID).orElseThrow();
        service.complete(unrelated, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, verified);
        assertFalse(service.runtimeSelectorIssues(accountId).getFirst().resolved(), "Checking a different tab must not clear the issue");

        var repair = service.repairSelectors(profile, anotherTab, "profile");
        service.claimNext(AGENT_ID).orElseThrow();
        service.complete(repair, AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, null, null, null, null, null, null, verified);
        assertTrue(service.runtimeSelectorIssues(accountId).getFirst().resolved(), "A verified fix of the same selector group resolves the issue");
        verifyNoInteractions(vacancySelection);
    }

    @Test
    void unrelatedPagesAndRepairActionsCannotBeSmuggledThroughRuntimeFailure() {
        var id = service.checkAuthorization(profile);
        service.claimNext(AGENT_ID).orElseThrow();
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.FAILED,
                "Error", null, null, null, null, null, null, broken(SelectorDiagnosticReport.PageType.VACANCY)));
        var valid = broken(SelectorDiagnosticReport.PageType.PROFILE);
        var withRepair = new SelectorDiagnosticReport(valid.pageType(), valid.checkedAt(), valid.status(), valid.message(),
                valid.rows(), valid.summary(), null, null, valid.tabId(), null, List.of("profile"), null);
        assertInvalidAgentData(() -> service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.FAILED,
                "Error", null, null, null, null, null, null, withRepair));
        assertTrue(service.runtimeSelectorIssues(accountId).isEmpty());
    }

    @Test
    void resumeTextFailureIncludesTheRequestedResumeIdentityForItsRefreshAction() {
        var id = service.readResumeText(profile, HH_RESUME_ID);
        service.claimNext(AGENT_ID).orElseThrow();
        var issue = new SelectorDiagnosticReport(SelectorDiagnosticReport.PageType.RESUME, START,
                SelectorDiagnosticReport.Status.CHECKED, "Не прочитан текст резюме",
                List.of(new SelectorDiagnosticReport.Row("resumeText", "blocks", "Разделы резюме", "css=main",
                        SelectorDiagnosticReport.Source.BUNDLED, SelectorDiagnosticReport.Result.FAIL, true, 0, 0, "Не прочитаны разделы")),
                SelectorDiagnosticReport.Summary.FAILED, null, null, UUID.randomUUID().toString(), null, List.of(), null);
        service.complete(id, AGENT_ID, BrowserAgentCommandResultStatus.FAILED, "Не прочитан текст",
                null, null, null, null, null, null, issue);
        var context = service.runtimeSelectorIssues(accountId).getFirst();
        assertEquals(HH_RESUME_ID, context.hhResumeId());
        assertNull(context.searchId());
        assertNull(context.applicationId());
        assertEquals(SelectorDiagnosticReport.PageType.RESUME, context.report().pageType());
    }
}
