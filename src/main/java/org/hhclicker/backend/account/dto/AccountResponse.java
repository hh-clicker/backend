package org.hhclicker.backend.account.dto;

import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String hhUserId,
        AuthorizationStatus authorizationStatus,
        Instant createdAt,
        Instant updatedAt
) {

    public static AccountResponse from(HhAccount account) {
        return new AccountResponse(
                account.id(),
                account.name(),
                account.hhUserId(),
                account.authorizationStatus(),
                account.createdAt(),
                account.updatedAt()
        );
    }
}

