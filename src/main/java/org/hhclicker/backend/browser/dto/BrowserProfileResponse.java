package org.hhclicker.backend.browser.dto;

import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.service.BrowserRuntimeSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BrowserProfileResponse(
        UUID id,
        List<String> tags,
        UUID accountId,
        String processStatus,
        Instant createdAt,
        Instant lastStartedAt,
        String profileDirectory
) {
    public static BrowserProfileResponse from(BrowserProfile profile, BrowserRuntimeSnapshot runtime) {
        boolean running = runtime.browserOpen() && profile.id().equals(runtime.activeProfileId());
        return new BrowserProfileResponse(profile.id(), List.of(), profile.accountId(), running ? "RUNNING" : "STOPPED",
                profile.createdAt(), null, "profiles/" + profile.id());
    }
}
