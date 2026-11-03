package org.hhclicker.backend.vacancy.stoplist.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidCompanyBanDataException extends ApplicationException {

    public InvalidCompanyBanDataException(String safeMessage) {
        super(
                ApplicationErrorCategory.VALIDATION,
                "INVALID_COMPANY_BAN_DATA",
                safeMessage
        );
    }

    public InvalidCompanyBanDataException(String safeMessage, Throwable cause) {
        super(
                ApplicationErrorCategory.VALIDATION,
                "INVALID_COMPANY_BAN_DATA",
                safeMessage,
                cause
        );
    }
}
