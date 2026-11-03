package org.hhclicker.backend.vacancy.catalog.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidVacancyDataException extends ApplicationException {

    public InvalidVacancyDataException(String safeMessage) {
        super(
                ApplicationErrorCategory.VALIDATION,
                "INVALID_VACANCY_DATA",
                safeMessage
        );
    }

    public InvalidVacancyDataException(String safeMessage, Throwable cause) {
        super(
                ApplicationErrorCategory.VALIDATION,
                "INVALID_VACANCY_DATA",
                safeMessage,
                cause
        );
    }
}
