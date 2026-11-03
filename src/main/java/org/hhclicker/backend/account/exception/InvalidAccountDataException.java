package org.hhclicker.backend.account.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidAccountDataException extends ApplicationException {

    public InvalidAccountDataException(String safeMessage) {
        super(
                ApplicationErrorCategory.VALIDATION,
                "INVALID_ACCOUNT_DATA",
                safeMessage
        );
    }
}

