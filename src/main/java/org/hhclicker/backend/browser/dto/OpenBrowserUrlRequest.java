package org.hhclicker.backend.browser.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OpenBrowserUrlRequest(
        @NotNull UUID accountId,
        @NotBlank @Size(max = 2048) String url
) {
}
