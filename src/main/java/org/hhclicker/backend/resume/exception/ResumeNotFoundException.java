package org.hhclicker.backend.resume.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class ResumeNotFoundException extends ApplicationException {

    public ResumeNotFoundException(UUID resumeId) {
        super(
                ApplicationErrorCategory.NOT_FOUND,
                "RESUME_NOT_FOUND",
                "Резюме %s не найдено.".formatted(resumeId)
        );
    }
}

