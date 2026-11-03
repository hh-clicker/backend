package org.hhclicker.backend.account.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class AccountNotFoundException extends ApplicationException {

    public AccountNotFoundException(UUID accountId) {
        super(
                ApplicationErrorCategory.NOT_FOUND,
                "ACCOUNT_NOT_FOUND",
                "HH-аккаунт %s не найден.".formatted(accountId)
        );
    }
}

