package org.hhclicker.backend.search.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class SearchAlreadyExistsException extends ApplicationException {

    public SearchAlreadyExistsException() {
        super(
                ApplicationErrorCategory.CONFLICT,
                "VACANCY_SEARCH_ALREADY_EXISTS",
                "Этот поиск HH уже сохранён для выбранного резюме."
        );
    }

    public SearchAlreadyExistsException(Throwable cause) {
        super(
                ApplicationErrorCategory.CONFLICT,
                "VACANCY_SEARCH_ALREADY_EXISTS",
                "Этот поиск HH уже сохранён для выбранного резюме.",
                cause
        );
    }
}

