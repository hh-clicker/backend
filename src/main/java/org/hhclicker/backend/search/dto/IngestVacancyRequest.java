package org.hhclicker.backend.search.dto;

import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;

import java.time.Instant;
import java.util.List;

/**
 * Нормализованная вакансия HH на границе HTTP. Прикладную проверку выполняет сервис приёма,
 * поэтому нарушение контракта возвращается стабильным машинным кодом.
 */
public record IngestVacancyRequest(
        @Schema(example = "118123456")
        String hhVacancyId,

        String title,

        @Schema(example = "3529")
        String employerId,

        String companyName,

        IngestVacancySalaryRequest salary,

        String location,

        String workFormat,

        String experience,

        Instant publishedAt,

        String salaryText,

        List<String> labels,

        Boolean hasQuestionnaire,

        Boolean alreadyApplied,

        Boolean closed,

        @Schema(description = "Порядок вакансии в выдаче HH")
        Integer position,

        JsonNode enrichment
) {

    public IngestVacancyRequest(
            String hhVacancyId, String title, String employerId, String companyName, IngestVacancySalaryRequest salary,
            String location, String workFormat, String experience, Instant publishedAt, String salaryText,
            List<String> labels, Boolean hasQuestionnaire, Boolean alreadyApplied, Boolean closed, Integer position
    ) {
        this(hhVacancyId, title, employerId, companyName, salary, location, workFormat, experience, publishedAt,
                salaryText, labels, hasQuestionnaire, alreadyApplied, closed, position, null);
    }

    public NormalizedVacancy toNormalizedVacancy() {
        return new NormalizedVacancy(
                hhVacancyId,
                title,
                employerId,
                companyName,
                salary == null ? null : salary.toNormalizedSalary(),
                location,
                workFormat,
                experience,
                publishedAt,
                salaryText,
                labels,
                hasQuestionnaire,
                Boolean.TRUE.equals(alreadyApplied),
                Boolean.TRUE.equals(closed),
                position == null ? 0 : position,
                enrichment
        );
    }
}
