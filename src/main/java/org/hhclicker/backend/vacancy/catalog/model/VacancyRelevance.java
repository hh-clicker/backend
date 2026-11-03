package org.hhclicker.backend.vacancy.catalog.model;

import java.time.Instant;

public record VacancyRelevance(
        VacancyRelevanceCategory category,
        VacancyRelevanceConfidence confidence,
        String reason,
        Instant analyzedAt
) {
}
