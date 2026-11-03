package org.hhclicker.backend.account.service;

public record UpdateAccountCommand(
        String name,
        String hhUserId
) {
}

