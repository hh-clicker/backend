package org.hhclicker.backend.search.dto;

import org.hhclicker.backend.search.service.SearchProfile;

import java.time.Instant;
import java.util.UUID;

public record SearchResponse(
        UUID id,
        String name,
        String sourceUrl,
        UUID resumeId,
        String resumeName,
        int pagesLimit,
        String status,
        long newVacancies,
        long totalVacancies,
        Instant createdAt,
        Instant lastScannedAt,
        String lastError,
        ScanProgressResponse scanProgress
) {
    public SearchResponse(
            UUID id, String name, String sourceUrl, UUID resumeId, String resumeName, int pagesLimit,
            String status, long newVacancies, long totalVacancies, Instant createdAt,
            Instant lastScannedAt, String lastError
    ) {
        this(id, name, sourceUrl, resumeId, resumeName, pagesLimit, status, newVacancies, totalVacancies,
                createdAt, lastScannedAt, lastError, null);
    }

    public static SearchResponse from(SearchProfile profile) {
        var search = profile.search();
        return new SearchResponse(
                search.id(), search.name(), search.sourceUrl(), search.resumeId(), profile.resumeName(),
                search.pagesLimit(), search.status().name(), search.newVacancies(), search.totalVacancies(),
                search.createdAt(), search.lastScannedAt(), search.lastError(),
                search.scanStartedAt() == null ? null : new ScanProgressResponse(
                        search.scanStartedAt(), search.scanCurrentPage(), search.scanTotalPages(),
                        search.scanCollected(), search.scanTotalResults())
        );
    }

    public record ScanProgressResponse(
            Instant startedAt,
            Integer currentPage,
            Integer totalPages,
            Integer collected,
            Integer totalResults
    ) {
    }
}
