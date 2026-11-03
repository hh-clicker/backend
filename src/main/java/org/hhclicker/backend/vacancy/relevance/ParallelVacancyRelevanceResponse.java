package org.hhclicker.backend.vacancy.relevance;

import java.util.UUID;

public record ParallelVacancyRelevanceResponse(
        UUID id, UUID resumeId, String status,
        int requestedCount, int evaluatedCount, int missingCount,
        String errorCode, String errorMessage,
        int evaluatedBefore, int totalCount, int batchCount, int completedBatches,
        boolean benchmark
) { }
