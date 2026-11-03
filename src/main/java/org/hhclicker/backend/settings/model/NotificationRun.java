package org.hhclicker.backend.settings.model;

import java.util.List;
import java.util.UUID;

public record NotificationRun(UUID id, UUID accountId, UUID resumeId, String resumeName,
                              long sequence, String status, String revision, long total, long succeeded,
                              long failed, long processed, long activeSeconds, long timedProcessed,
                              List<String> attentionIds, String mode) {
    public NotificationRun(UUID id, UUID accountId, UUID resumeId, String resumeName,
                           long sequence, String status, String revision, long total, long succeeded,
                           long failed, long processed, long activeSeconds, long timedProcessed,
                           List<String> attentionIds) {
        this(id, accountId, resumeId, resumeName, sequence, status, revision, total, succeeded,
                failed, processed, activeSeconds, timedProcessed, attentionIds, "APPLY");
    }
}
