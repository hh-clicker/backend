package org.hhclicker.backend.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedSalary;

public record IngestVacancySalaryRequest(
        Long from,

        Long to,

        @Schema(allowableValues = {"RUR", "USD", "EUR"}, example = "RUR")
        String currency,

        Boolean gross
) {

    public NormalizedSalary toNormalizedSalary() {
        return new NormalizedSalary(from, to, currency, Boolean.TRUE.equals(gross));
    }
}
