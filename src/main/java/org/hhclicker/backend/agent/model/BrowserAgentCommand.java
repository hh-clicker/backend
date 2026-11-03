package org.hhclicker.backend.agent.model;

import org.hhclicker.backend.browser.model.BrowserAuthorizationResult;
import org.hhclicker.backend.browser.model.BrowserResumeSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BrowserAgentCommand(
        UUID id,
        BrowserAgentCommandType type,
        BrowserAgentCommandPayload payload,
        BrowserAgentCommandStatus status,
        String agentId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage,
        BrowserAuthorizationResult authorization,
        List<BrowserResumeSummary> resumes,
        org.hhclicker.backend.application.model.ApplicationModels.Outcome application
) {
    public BrowserAgentCommand(UUID id, BrowserAgentCommandType type, BrowserAgentCommandPayload payload,
                               BrowserAgentCommandStatus status, String agentId, Instant createdAt, Instant startedAt,
                               Instant completedAt, String errorMessage, BrowserAuthorizationResult authorization,
                               List<BrowserResumeSummary> resumes) {
        this(id, type, payload, status, agentId, createdAt, startedAt, completedAt, errorMessage, authorization,
                resumes, null);
    }
}
