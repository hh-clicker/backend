package org.hhclicker.backend.resume.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class ResumeHasDependentDataException extends ApplicationException {

    public ResumeHasDependentDataException(Throwable cause) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "RESUME_HAS_DEPENDENT_DATA",
                "Нельзя удалить резюме, пока с ним связаны поиски или другие данные.",
                cause
        );
    }
}

