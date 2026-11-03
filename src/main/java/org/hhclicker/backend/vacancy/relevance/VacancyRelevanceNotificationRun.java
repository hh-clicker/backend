package org.hhclicker.backend.vacancy.relevance;

import java.util.UUID;

public record VacancyRelevanceNotificationRun(
        UUID id,
        UUID accountId,
        UUID resumeId,
        String resumeName,
        String status,
        String revision,
        boolean finalBatch,
        int requestedCount,
        int evaluatedCount,
        int missingCount,
        String errorMessage
) {
}
