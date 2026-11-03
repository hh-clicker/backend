package org.hhclicker.backend.account.model;

import java.time.Instant;
import java.util.UUID;

public record HhAccount(
        UUID id,
        String name,
        String hhUserId,
        AuthorizationStatus authorizationStatus,
        Instant createdAt,
        Instant updatedAt
) {
}

