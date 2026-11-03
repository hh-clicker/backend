package org.hhclicker.backend.resume.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidResumeDataException extends ApplicationException {

    public InvalidResumeDataException(String safeMessage) {
        super(
                ApplicationErrorCategory.VALIDATION,
                "INVALID_RESUME_DATA",
                safeMessage
        );
    }
}

