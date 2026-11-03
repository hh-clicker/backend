package org.hhclicker.backend.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.agent.model.BrowserAgentSnapshot;

import java.time.Instant;
import java.util.UUID;

public record BrowserAgentStatusResponse(
        @Schema(nullable = true)
        String agentId,

        @Schema(nullable = true)
        String version,

        BrowserAgentRuntimeStatus status,

        @Schema(nullable = true)
        Instant lastHeartbeatAt,

        long offlineAfterSeconds,
        boolean browserOpen,
        UUID activeProfileId,
        UUID generationCheckId
) {
    public static BrowserAgentStatusResponse from(BrowserAgentSnapshot snapshot) {
        return new BrowserAgentStatusResponse(
                snapshot.agentId(),
                snapshot.version(),
                snapshot.status(),
                snapshot.lastHeartbeatAt(),
                snapshot.offlineAfterSeconds(),
                snapshot.browserOpen(),
                snapshot.activeProfileId(), null
        );
    }

    public BrowserAgentStatusResponse withGenerationCheckId(UUID id) {
        return new BrowserAgentStatusResponse(agentId, version, status, lastHeartbeatAt, offlineAfterSeconds, browserOpen, activeProfileId, id);
    }
}
