package org.hhclicker.backend.overview.service;

import java.time.Instant;
import java.util.UUID;

public record OverviewRecentSearch(
        UUID id,
        String name,
        Instant createdAt,
        long newVacancies,
        String status
) {
}

