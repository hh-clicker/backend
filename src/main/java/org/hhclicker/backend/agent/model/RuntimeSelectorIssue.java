package org.hhclicker.backend.agent.model;

import org.hhclicker.backend.browser.model.SelectorDiagnosticReport;
import java.time.Instant;
import java.util.UUID;

/** Ephemeral context from a completed command, without HTML, URLs or extracted user values. */
public record RuntimeSelectorIssue(UUID commandId, UUID searchId, UUID applicationId,
                                   Instant completedAt, SelectorDiagnosticReport report, boolean resolved, String hhResumeId) {}
