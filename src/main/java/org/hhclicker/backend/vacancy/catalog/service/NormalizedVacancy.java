package org.hhclicker.backend.vacancy.catalog.service;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Нормализованная вакансия HH на входе прикладного сервиса приёма результатов поиска.
 * Модель транспорта Browser Agent или HTTP DTO сюда не проникают.
 */
public record NormalizedVacancy(
        String hhVacancyId,
        String title,
        String employerId,
        String companyName,
        NormalizedSalary salary,
        String location,
        String workFormat,
        String experience,
        Instant publishedAt,
        String salaryText,
        List<String> labels,
        Boolean hasQuestionnaire,
        boolean alreadyApplied,
        boolean closed,
        int position,
        JsonNode enrichment
) {
    public NormalizedVacancy(
            String hhVacancyId, String title, String employerId, String companyName, NormalizedSalary salary,
            String location, String workFormat, String experience, Instant publishedAt, String salaryText,
            List<String> labels, Boolean hasQuestionnaire, boolean alreadyApplied, boolean closed, int position
    ) {
        this(hhVacancyId, title, employerId, companyName, salary, location, workFormat, experience, publishedAt,
                salaryText, labels, hasQuestionnaire, alreadyApplied, closed, position, null);
    }
}
