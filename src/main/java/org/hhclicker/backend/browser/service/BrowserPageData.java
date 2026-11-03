package org.hhclicker.backend.browser.service;

import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.browser.model.BrowserProfile;

public record BrowserPageData(
        BrowserRuntimeSnapshot runtime,
        AuthorizationStatus authorizationStatus,
        BrowserProfile profile
) {
}

