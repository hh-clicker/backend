package org.hhclicker.backend.vacancy.catalog.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class VacancyNotFoundException extends ApplicationException {

    public VacancyNotFoundException(UUID vacancyId) {
        super(
                ApplicationErrorCategory.NOT_FOUND,
                "VACANCY_NOT_FOUND",
                "Вакансия %s не найдена.".formatted(vacancyId)
        );
    }
}
