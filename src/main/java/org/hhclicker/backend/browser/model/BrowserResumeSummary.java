package org.hhclicker.backend.browser.model;

public record BrowserResumeSummary(String hhResumeId, String name, String plainText) {
    public BrowserResumeSummary(String hhResumeId, String name) {
        this(hhResumeId, name, null);
    }
}
