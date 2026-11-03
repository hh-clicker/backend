package org.hhclicker.backend.agent.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;

import java.time.Instant;
import java.util.List;

/**
 * Only fields actually present in search cards, never a detail-page snapshot.
 */
public record BrowserSearchVacancyRequest(
        @NotBlank @Pattern(regexp = "[0-9]{1,64}") String hhVacancyId,
        @NotBlank @Size(max = 500) String title,
        @Pattern(regexp = "[0-9]{1,64}") String employerId,
        @NotBlank @Size(max = 300) String companyName,
        @Size(max = 500) String salaryText,
        @jakarta.validation.Valid BrowserSearchSalaryRequest salary,
        @Size(max = 300) String location,
        @Size(max = 120) String workFormat,
        @Size(max = 120) String experience,
        @NotNull @Size(max = 100) List<@NotNull @Size(max = 200) String> labels,
        @Min(0) @Max(1999) int position,
        Instant publishedAt,
        JsonNode enrichment
) {
    public NormalizedVacancy toModel() {
        return new NormalizedVacancy(hhVacancyId, title, employerId, companyName, salary == null ? null : salary.toModel(),
                location, workFormat, experience, publishedAt, salaryText, labels, questionnairePresent(), false, false, position,
                enrichment);
    }

    private Boolean questionnairePresent() {
        if (enrichment == null) return null;
        JsonNode value = enrichment.get("userTestPresent");
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }
}
