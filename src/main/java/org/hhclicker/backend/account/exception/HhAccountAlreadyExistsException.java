package org.hhclicker.backend.account.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class HhAccountAlreadyExistsException extends ApplicationException {

    public HhAccountAlreadyExistsException(String hhUserId) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "HH_ACCOUNT_ALREADY_EXISTS",
                "HH-аккаунт пользователя %s уже добавлен.".formatted(hhUserId)
        );
    }

    public HhAccountAlreadyExistsException(String hhUserId, Throwable cause) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "HH_ACCOUNT_ALREADY_EXISTS",
                "HH-аккаунт пользователя %s уже добавлен.".formatted(hhUserId),
                cause
        );
    }
}

