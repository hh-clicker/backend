package org.hhclicker.backend.search.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class SearchNotFoundException extends ApplicationException {

    public SearchNotFoundException(UUID searchId) {
        super(
                ApplicationErrorCategory.NOT_FOUND,
                "VACANCY_SEARCH_NOT_FOUND",
                "Поиск %s не найден.".formatted(searchId)
        );
    }
}

