package org.hhclicker.backend.resume.model;

public record ResumeStatistics(
        long searches,
        long newVacancies,
        long queuedVacancies,
        long successfulApplications,
        long blockedCompanies
) {

    public static ResumeStatistics empty() {
        return new ResumeStatistics(0, 0, 0, 0, 0);
    }
}

