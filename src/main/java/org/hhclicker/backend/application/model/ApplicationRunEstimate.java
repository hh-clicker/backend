package org.hhclicker.backend.application.model;

import java.util.OptionalLong;

public final class ApplicationRunEstimate {
    private ApplicationRunEstimate() {
    }

    public static OptionalLong remainingSeconds(String status, long total, long processed,
                                                long activeSeconds, long timedProcessed) {
        long remaining = Math.max(0, total - Math.min(total, processed));
        if (!active(status) || remaining == 0 || timedProcessed <= 0 || activeSeconds <= 0) {
            return OptionalLong.empty();
        }
        double seconds = (double) activeSeconds / timedProcessed * remaining;
        if (!Double.isFinite(seconds) || seconds <= 0) return OptionalLong.empty();
        return OptionalLong.of(Math.max(1, Math.round(seconds)));
    }

    private static boolean active(String status) {
        return status.equals("RUNNING") || status.startsWith("PAUSED_");
    }
}
