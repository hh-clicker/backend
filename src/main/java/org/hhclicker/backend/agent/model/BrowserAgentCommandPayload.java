package org.hhclicker.backend.agent.model;

import org.hhclicker.backend.browser.model.BrowserProfile;

import java.util.UUID;

public record BrowserAgentCommandPayload(
        UUID profileId,
        String url,
        UUID searchId,
        Integer pagesLimit,
        org.hhclicker.backend.application.model.ApplicationModels.Task application,
        UUID vacancyId,
        org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType diagnosticPageType,
        org.hhclicker.backend.browser.model.SelectorDiagnosticTarget diagnosticTarget
) {
    public BrowserAgentCommandPayload(UUID profileId, String url, UUID searchId, Integer pagesLimit,
            org.hhclicker.backend.application.model.ApplicationModels.Task application, UUID vacancyId,
            org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType diagnosticPageType) {
        this(profileId, url, searchId, pagesLimit, application, vacancyId, diagnosticPageType, null);
    }
    public BrowserAgentCommandPayload(UUID profileId, String url, UUID searchId, Integer pagesLimit,
                                      org.hhclicker.backend.application.model.ApplicationModels.Task application, UUID vacancyId) {
        this(profileId, url, searchId, pagesLimit, application, vacancyId, null);
    }
    public BrowserAgentCommandPayload(UUID profileId, String url, UUID searchId, Integer pagesLimit,
                                      org.hhclicker.backend.application.model.ApplicationModels.Task application) {
        this(profileId, url, searchId, pagesLimit, application, null);
    }

    public BrowserAgentCommandPayload(UUID profileId, String url, UUID searchId, Integer pagesLimit) {
        this(profileId, url, searchId, pagesLimit, null, null);
    }

    public static BrowserAgentCommandPayload from(BrowserProfile profile) {
        return new BrowserAgentCommandPayload(profile.id(), null, null, null);
    }

    public static BrowserAgentCommandPayload forUrl(BrowserProfile profile, String url) {
        return new BrowserAgentCommandPayload(profile.id(), url, null, null);
    }

    public static BrowserAgentCommandPayload forApplicationInspection(BrowserProfile profile, UUID vacancyId,
                                                                       String hhVacancyId) {
        return new BrowserAgentCommandPayload(profile.id(), "https://hh.ru/vacancy/" + hhVacancyId,
                null, null, null, vacancyId);
    }
}
