package org.hhclicker.backend.resume.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class ResumeAlreadyExistsException extends ApplicationException {

    public ResumeAlreadyExistsException(String hhResumeId) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "HH_RESUME_ALREADY_EXISTS",
                "Резюме HH %s уже добавлено.".formatted(hhResumeId)
        );
    }

    public ResumeAlreadyExistsException(String hhResumeId, Throwable cause) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "HH_RESUME_ALREADY_EXISTS",
                "Резюме HH %s уже добавлено.".formatted(hhResumeId),
                cause
        );
    }
}

