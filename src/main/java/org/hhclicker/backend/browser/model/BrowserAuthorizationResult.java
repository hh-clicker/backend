package org.hhclicker.backend.browser.model;

import org.hhclicker.backend.account.model.AuthorizationStatus;

public record BrowserAuthorizationResult(AuthorizationStatus status, String message) {
}
