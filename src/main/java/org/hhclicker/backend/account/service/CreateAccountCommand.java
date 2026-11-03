package org.hhclicker.backend.account.service;

public record CreateAccountCommand(
        String name,
        String hhUserId
) {
}

