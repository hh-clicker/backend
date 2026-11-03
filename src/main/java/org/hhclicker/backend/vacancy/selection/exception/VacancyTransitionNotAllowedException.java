package org.hhclicker.backend.vacancy.selection.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class VacancyTransitionNotAllowedException extends ApplicationException {

    public VacancyTransitionNotAllowedException(String safeMessage) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "VACANCY_TRANSITION_NOT_ALLOWED",
                safeMessage
        );
    }
}
