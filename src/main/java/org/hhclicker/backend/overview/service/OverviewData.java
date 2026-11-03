package org.hhclicker.backend.overview.service;

import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.resume.service.ResumeProfile;

import java.util.List;

public record OverviewData(
        List<HhAccount> accounts,
        HhAccount selectedAccount,
        List<ResumeProfile> resumes,
        ResumeProfile selectedResume,
        List<OverviewMetric> metrics,
        List<OverviewRecentSearch> recentSearches,
        List<org.hhclicker.backend.application.model.ApplicationModels.Run> applicationRuns,
        List<org.hhclicker.backend.application.model.ApplicationModels.Attention> attentionTasks
) {
}
