package org.hhclicker.backend.vacancy.catalog.model;

public record VacancySalary(
        Long from,
        Long to,
        SalaryCurrency currency,
        boolean gross
) {
}
