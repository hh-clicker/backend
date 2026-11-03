package org.hhclicker.backend.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedSalary;

public record BrowserSearchSalaryRequest(
        @PositiveOrZero Long from,
        @PositiveOrZero Long to,
        @NotNull @Pattern(regexp = "RUR") String currency,
        boolean gross
) {
    public NormalizedSalary toModel() {
        return new NormalizedSalary(from, to, currency, gross);
    }
}
