package org.hhclicker.backend.browser.model;

/** Immutable input for a read-only full diagnostic; URLs are resolved by backend. */
public record SelectorDiagnosticTarget(String hhResumeId, String searchUrl, String searchName, String tabId, String repairGroup) {
    public SelectorDiagnosticTarget(String hhResumeId, String searchUrl, String searchName, String tabId) {
        this(hhResumeId, searchUrl, searchName, tabId, null);
    }
}
