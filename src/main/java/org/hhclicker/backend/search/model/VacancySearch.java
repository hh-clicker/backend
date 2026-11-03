package org.hhclicker.backend.search.model;

import java.time.Instant;
import java.util.UUID;

public record VacancySearch(
        UUID id,
        UUID resumeId,
        String name,
        String sourceUrl,
        String sourceHash,
        int pagesLimit,
        SearchStatus status,
        long newVacancies,
        long totalVacancies,
        Instant lastScannedAt,
        String lastError,
        Instant scanStartedAt,
        Integer scanCurrentPage,
        Integer scanTotalPages,
        Integer scanCollected,
        Integer scanTotalResults,
        Instant createdAt,
        Instant updatedAt
) {
    public VacancySearch(
            UUID id, UUID resumeId, String name, String sourceUrl, String sourceHash, int pagesLimit,
            SearchStatus status, long newVacancies, long totalVacancies, Instant lastScannedAt,
            String lastError, Instant createdAt, Instant updatedAt
    ) {
        this(id, resumeId, name, sourceUrl, sourceHash, pagesLimit, status, newVacancies, totalVacancies,
                lastScannedAt, lastError, null, null, null, null, null, createdAt, updatedAt);
    }
}
