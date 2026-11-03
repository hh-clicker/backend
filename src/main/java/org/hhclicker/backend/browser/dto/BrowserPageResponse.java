package org.hhclicker.backend.browser.dto;

import org.hhclicker.backend.browser.service.BrowserPageData;

public record BrowserPageResponse(
        BrowserAgentDetailsResponse agent,
        BrowserProfileResponse profile
) {
    public static BrowserPageResponse from(BrowserPageData data) {
        return new BrowserPageResponse(
                new BrowserAgentDetailsResponse(data.runtime().agentStatus(), data.authorizationStatus().name(),
                        data.runtime().agentVersion(), "HTTP polling", "Локальный компьютер", "Google Chrome · Playwright"),
                data.profile() == null ? null : BrowserProfileResponse.from(data.profile(), data.runtime())
        );
    }

    public record BrowserAgentDetailsResponse(
            String status,
            String authorizationStatus,
            String version,
            String endpoint,
            String host,
            String browserVersion
    ) {
    }
}
