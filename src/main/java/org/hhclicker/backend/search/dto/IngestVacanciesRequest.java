package org.hhclicker.backend.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;

import java.util.List;

public record IngestVacanciesRequest(
        @Schema(description = "Не больше 500 вакансий за запрос: крупный скан агент разбивает на части")
        @NotNull
        @Size(max = 500)
        List<@NotNull @Valid IngestVacancyRequest> vacancies
) {

    public List<NormalizedVacancy> toNormalizedVacancies() {
        if (vacancies == null) {
            return List.of();
        }
        return vacancies.stream()
                .map(IngestVacancyRequest::toNormalizedVacancy)
                .toList();
    }
}
