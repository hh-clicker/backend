package org.hhclicker.backend.vacancy.relevance;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record VacancyRelevanceRequest(
        @NotEmpty @Size(max = 300) List<@NotNull UUID> vacancyIds,
        Boolean finalBatch,
        @Positive Integer totalCount,
        @PositiveOrZero Integer evaluatedBefore
) {
    public VacancyRelevanceRequest(List<UUID> vacancyIds) {
        this(vacancyIds, true, vacancyIds.size(), 0);
    }
}
