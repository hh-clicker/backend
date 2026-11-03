package org.hhclicker.backend.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MarketAnalysisEstimateTests {
    private final Instant startedAt = Instant.parse("2026-10-19T10:00:00Z");

    @Test
    void estimatesRemainingWholeBatchesAfterTheFirstCompletedBatch() {
        var estimate = MarketAnalysisEstimate.remainingSeconds("RUNNING", "EXTRACT", startedAt,
                startedAt.plusSeconds(120), 8, 19, 8);

        assertEquals(240, estimate.orElseThrow());
    }

    @Test
    void estimateUsesCompletedBatchCountInsteadOfPerVacancySpeed() {
        var estimate = MarketAnalysisEstimate.remainingSeconds("RUNNING", "EXTRACT", startedAt,
                startedAt.plusSeconds(180), 16, 200, 8);

        assertEquals(2070, estimate.orElseThrow());
    }

    @Test
    void hidesEstimateUntilThereIsProgressAndAfterCompletionOrFailure() {
        assertTrue(MarketAnalysisEstimate.remainingSeconds("RUNNING", "EXTRACT", startedAt,
                startedAt.plusSeconds(120), 0, 200, 8).isEmpty());
        assertTrue(MarketAnalysisEstimate.remainingSeconds("COMPLETED", "DONE", startedAt,
                startedAt.plusSeconds(120), 200, 200, 8).isEmpty());
        assertTrue(MarketAnalysisEstimate.remainingSeconds("FAILED", "EXTRACT", startedAt,
                startedAt.plusSeconds(120), 8, 200, 8).isEmpty());
    }
}
