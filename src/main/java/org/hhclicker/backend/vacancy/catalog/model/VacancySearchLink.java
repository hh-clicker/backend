package org.hhclicker.backend.vacancy.catalog.model;

import java.time.Instant;
import java.util.UUID;

public record VacancySearchLink(
        UUID id,
        UUID vacancyId,
        UUID searchId,
        int position,
        Instant createdAt,
        Instant updatedAt
) {
}
