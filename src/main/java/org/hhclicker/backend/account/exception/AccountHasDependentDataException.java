package org.hhclicker.backend.account.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class AccountHasDependentDataException extends ApplicationException {

    public AccountHasDependentDataException(UUID accountId, Throwable cause) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "ACCOUNT_HAS_DEPENDENT_DATA",
                "Нельзя удалить HH-аккаунт %s, пока с ним связаны резюме или другие данные.".formatted(accountId),
                cause
        );
    }
}

