package org.hhclicker.backend.browser.service;

import java.util.UUID;

public record BrowserRuntimeSnapshot(
        String agentStatus,
        String agentVersion,
        boolean browserOpen,
        UUID activeProfileId
) {
}

