package org.hhclicker.backend.resume.dto;

import org.hhclicker.backend.resume.model.ResumeStatistics;

public record ResumeStatisticsResponse(
        long searches,
        long newVacancies,
        long queuedVacancies,
        long successfulApplications,
        long blockedCompanies
) {

    static ResumeStatisticsResponse from(ResumeStatistics statistics) {
        return new ResumeStatisticsResponse(
                statistics.searches(),
                statistics.newVacancies(),
                statistics.queuedVacancies(),
                statistics.successfulApplications(),
                statistics.blockedCompanies()
        );
    }
}

