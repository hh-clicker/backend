package org.hhclicker.backend.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.browser.model.BrowserAuthorizationResult;

public record BrowserAuthorizationResultRequest(
        @NotNull AuthorizationStatus status,
        @Size(max = 1000) String message
) {
    public BrowserAuthorizationResult toModel() {
        return new BrowserAuthorizationResult(status, message);
    }
}
