package org.hhclicker.backend.overview.service;

public record OverviewMetric(
        String id,
        String label,
        long value,
        String hint,
        String tone
) {
}

