package org.hhclicker.backend.browser.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StartBrowserRequest(@NotNull UUID accountId) {
}

