package org.hhclicker.backend.application.dto;

import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.RunMode;
import org.hhclicker.backend.application.model.ApplicationModels.RunSummary;

import java.time.Instant;
import java.util.UUID;

public record ApplicationRunSummaryResponse(UUID id, long sequence, UUID resumeId, String resumeName,
                                            RunStatus status, Instant createdAt, long total, long attention,
                                            String revision, Long estimatedRemainingSeconds, RunMode mode) {
    public static ApplicationRunSummaryResponse from(RunSummary run) {
        return new ApplicationRunSummaryResponse(run.id(), run.sequence(), run.resumeId(), run.resumeName(),
                run.status(), run.createdAt(), run.total(), run.attention(), run.revision(),
                run.estimatedRemainingSeconds(), run.mode());
    }
}
