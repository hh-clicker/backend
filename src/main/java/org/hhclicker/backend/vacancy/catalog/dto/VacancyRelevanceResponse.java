package org.hhclicker.backend.vacancy.catalog.dto;

import org.hhclicker.backend.vacancy.catalog.model.VacancyRelevance;
import org.hhclicker.backend.vacancy.catalog.model.VacancyRelevanceCategory;
import org.hhclicker.backend.vacancy.catalog.model.VacancyRelevanceConfidence;

import java.time.Instant;

public record VacancyRelevanceResponse(
        VacancyRelevanceCategory category,
        VacancyRelevanceConfidence confidence,
        String reason,
        Instant analyzedAt
) {
    public static VacancyRelevanceResponse from(VacancyRelevance relevance) {
        return relevance == null ? null : new VacancyRelevanceResponse(
                relevance.category(), relevance.confidence(), relevance.reason(), relevance.analyzedAt());
    }
}
