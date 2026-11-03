package org.hhclicker.backend.vacancy.stoplist.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class CompanyBanAlreadyExistsException extends ApplicationException {

    public CompanyBanAlreadyExistsException(String safeMessage) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "COMPANY_BAN_ALREADY_EXISTS",
                safeMessage
        );
    }

    public CompanyBanAlreadyExistsException(String safeMessage, Throwable cause) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "COMPANY_BAN_ALREADY_EXISTS",
                safeMessage,
                cause
        );
    }
}
