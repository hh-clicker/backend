package org.hhclicker.backend.browser.model;

import java.time.Instant;
import java.util.UUID;

public record BrowserProfile(
        UUID id,
        UUID accountId,
        Instant createdAt,
        Instant updatedAt
) {
}
