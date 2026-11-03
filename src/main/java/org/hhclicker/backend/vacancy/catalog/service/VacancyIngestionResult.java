package org.hhclicker.backend.vacancy.catalog.service;

import java.util.UUID;

public record VacancyIngestionResult(
        UUID searchId,
        int received,
        int created,
        int updated,
        long newVacancies,
        long totalVacancies
) {
}
