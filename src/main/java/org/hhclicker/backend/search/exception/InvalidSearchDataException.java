package org.hhclicker.backend.search.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidSearchDataException extends ApplicationException {

    public InvalidSearchDataException(String safeMessage) {
        super(ApplicationErrorCategory.VALIDATION, "INVALID_SEARCH_DATA", safeMessage);
    }
}

