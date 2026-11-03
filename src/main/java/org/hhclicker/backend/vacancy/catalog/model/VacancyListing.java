package org.hhclicker.backend.vacancy.catalog.model;

import java.util.UUID;

/**
 * Узкая read-проекция: вакансия вместе с поиском, в контексте которого её запросили.
 */
public record VacancyListing(
        Vacancy vacancy,
        UUID searchId,
        boolean hasUnconfirmedApplication
) {
    public VacancyListing(Vacancy vacancy, UUID searchId) {
        this(vacancy, searchId, false);
    }
}
