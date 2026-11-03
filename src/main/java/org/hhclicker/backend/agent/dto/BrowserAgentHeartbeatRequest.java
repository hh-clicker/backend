package org.hhclicker.backend.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;

import java.util.UUID;

public record BrowserAgentHeartbeatRequest(
        @Schema(example = "local-browser-agent")
        @NotBlank
        @Size(max = 100)
        String agentId,

        @Schema(example = "0.0.0")
        @NotBlank
        @Size(max = 50)
        String version,

        @Schema(example = "READY")
        @NotNull
        BrowserAgentRuntimeStatus status,

        boolean browserOpen,

        UUID activeProfileId,
        @jakarta.validation.Valid org.hhclicker.backend.agent.service.GenerationStatusService.Report generation
) {
    @AssertTrue(message = "Browser Agent cannot report OFFLINE status")
    public boolean isRuntimeStatusReportable() {
        return status != BrowserAgentRuntimeStatus.OFFLINE;
    }
}
