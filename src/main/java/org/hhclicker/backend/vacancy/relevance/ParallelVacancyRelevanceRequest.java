package org.hhclicker.backend.vacancy.relevance;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ParallelVacancyRelevanceRequest(
        @NotEmpty @Size(max = 10_000) List<@NotNull UUID> vacancyIds,
        @Positive Integer totalCount,
        @PositiveOrZero Integer evaluatedBefore,
        Boolean benchmark
) { }
