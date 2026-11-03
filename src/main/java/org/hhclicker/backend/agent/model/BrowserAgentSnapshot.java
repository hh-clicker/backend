package org.hhclicker.backend.agent.model;

import java.time.Instant;
import java.util.UUID;

public record BrowserAgentSnapshot(
        String agentId,
        String version,
        BrowserAgentRuntimeStatus status,
        Instant lastHeartbeatAt,
        long offlineAfterSeconds,
        boolean browserOpen,
        UUID activeProfileId
) {
    public static BrowserAgentSnapshot offline(long offlineAfterSeconds) {
        return new BrowserAgentSnapshot(
                null,
                null,
                BrowserAgentRuntimeStatus.OFFLINE,
                null,
                offlineAfterSeconds,
                false,
                null
        );
    }
}
