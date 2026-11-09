package org.hhclicker.backend.browser.service;

import org.hhclicker.backend.browser.model.BrowserProfile;

import java.util.UUID;

public interface BrowserRuntimeGateway {

    BrowserRuntimeSnapshot getSnapshot();

    UUID open(BrowserProfile profile);

    UUID openUrl(BrowserProfile profile, String url);

    UUID focusVacancy(BrowserProfile profile, String hhVacancyId);

    UUID checkAuthorization(BrowserProfile profile);

    UUID discoverResumes(BrowserProfile profile);

    UUID diagnoseSelectors(BrowserProfile profile, org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType pageType);

    UUID diagnoseSelectors(BrowserProfile profile, org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType mode, UUID resumeId);
    UUID diagnoseSelectors(BrowserProfile profile, org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType mode, UUID resumeId, UUID tabId);

    UUID focusDiagnostic(BrowserProfile profile, UUID tabId);
    UUID repairSelectors(BrowserProfile profile, UUID tabId, String group);

    UUID readResumeText(BrowserProfile profile, String hhResumeId);

    UUID close();
}
