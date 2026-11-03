package org.hhclicker.backend.vacancy.relevance;

import java.util.UUID;

public record VacancyRelevanceRunResponse(
        UUID id,
        UUID resumeId,
        String status,
        int requestedCount,
        int evaluatedCount,
        int missingCount,
        String errorCode,
        String errorMessage
) {
}
