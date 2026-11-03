package org.hhclicker.backend.settings.model;

import jakarta.validation.constraints.NotNull;

public record NotificationSettings(
        @NotNull Boolean desktopEnabled, @NotNull Boolean soundEnabled,
        @NotNull Boolean notifyRunCompleted, @NotNull Boolean notifyRunPaused,
        @NotNull Boolean notifyAttentionRequired, @NotNull Boolean notifyRelevanceCompleted,
        @NotNull Boolean notifyMarketCompleted) {
    public NotificationSettings(Boolean desktopEnabled, Boolean soundEnabled, Boolean notifyRunCompleted,
                                Boolean notifyRunPaused, Boolean notifyAttentionRequired,
                                Boolean notifyRelevanceCompleted) {
        this(desktopEnabled, soundEnabled, notifyRunCompleted, notifyRunPaused,
                notifyAttentionRequired, notifyRelevanceCompleted, true);
    }
}
