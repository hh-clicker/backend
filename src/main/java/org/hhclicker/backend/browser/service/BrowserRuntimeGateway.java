package org.hhclicker.backend.browser.service;

import org.hhclicker.backend.browser.model.BrowserProfile;

import java.util.UUID;

public interface BrowserRuntimeGateway {

    BrowserRuntimeSnapshot getSnapshot();

    UUID open(BrowserProfile profile);

    UUID openUrl(BrowserProfile profile, String url);

    UUID checkAuthorization(BrowserProfile profile);

    UUID discoverResumes(BrowserProfile profile);

    UUID readResumeText(BrowserProfile profile, String hhResumeId);

    UUID close();
}
