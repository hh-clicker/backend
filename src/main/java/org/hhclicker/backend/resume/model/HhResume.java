package org.hhclicker.backend.resume.model;

import java.time.Instant;
import java.util.UUID;

public record HhResume(
        UUID id,
        UUID accountId,
        String hhResumeId,
        String name,
        ResumeAutomationSettings settings,
        Instant createdAt,
        Instant updatedAt,
        String plainText,
        Instant textLoadedAt
) {
    public HhResume(UUID id, UUID accountId, String hhResumeId, String name, ResumeAutomationSettings settings,
                    Instant createdAt, Instant updatedAt) {
        this(id, accountId, hhResumeId, name, settings, createdAt, updatedAt, null, null);
    }
}
