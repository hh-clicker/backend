package org.hhclicker.backend.market;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/** Rough ETA based on completed extraction batches, including model queue time. */
final class MarketAnalysisEstimate {
    private MarketAnalysisEstimate() { }

    static OptionalLong remainingSeconds(String status, String phase, Instant startedAt, Instant now,
                                         int processed, int total, int batchSize) {
        if (!"RUNNING".equals(status) || !"EXTRACT".equals(phase) || startedAt == null
                || processed <= 0 || processed >= total || batchSize <= 0) return OptionalLong.empty();
        long elapsed = Duration.between(startedAt, now).getSeconds();
        if (elapsed <= 0) return OptionalLong.empty();
        long completedBatches = (processed - 1L) / batchSize + 1;
        long remainingBatches = (total - processed - 1L) / batchSize + 1;
        double estimate = (double) elapsed / completedBatches * remainingBatches;
        if (!Double.isFinite(estimate) || estimate <= 0) return OptionalLong.empty();
        return OptionalLong.of(Math.max(1, Math.round(estimate)));
    }
}
