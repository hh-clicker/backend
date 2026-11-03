package org.hhclicker.backend.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandPayload;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandType;
import org.hhclicker.backend.browser.model.BrowserAuthorizationResult;
import org.hhclicker.backend.browser.model.BrowserResumeSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BrowserAgentCommandResponse(
        UUID id,
        BrowserAgentCommandType type,
        BrowserAgentCommandPayload payload,
        BrowserAgentCommandStatus status,

        @Schema(nullable = true)
        String agentId,

        Instant createdAt,

        @Schema(nullable = true)
        Instant startedAt,

        @Schema(nullable = true)
        Instant completedAt,

        @Schema(nullable = true)
        String errorMessage,
        BrowserAuthorizationResult authorization,
        List<BrowserResumeSummary> resumes,
        org.hhclicker.backend.application.model.ApplicationModels.Outcome application
) {
    public static BrowserAgentCommandResponse from(BrowserAgentCommand command) {
        return new BrowserAgentCommandResponse(
                command.id(),
                command.type(),
                command.payload(),
                command.status(),
                command.agentId(),
                command.createdAt(),
                command.startedAt(),
                command.completedAt(),
                command.errorMessage(),
                command.authorization(),
                command.resumes(),
                command.application()
        );
    }
}
