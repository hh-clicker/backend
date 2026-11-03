package org.hhclicker.backend.resume.dto;

import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.service.ResumeProfile;

import java.util.UUID;

public record ResumeProfileResponse(
        UUID id,
        String hhResumeId,
        String name,
        UUID accountId,
        String accountName,
        String publicUrl,
        ResumeAutomationSettingsResponse settings,
        ResumeStatisticsResponse statistics,
        String plainText,
        java.time.Instant textLoadedAt
) {

    public static ResumeProfileResponse from(ResumeProfile profile) {
        HhResume resume = profile.resume();
        return new ResumeProfileResponse(
                resume.id(),
                resume.hhResumeId(),
                resume.name(),
                resume.accountId(),
                profile.accountName(),
                "https://hh.ru/resume/%s".formatted(resume.hhResumeId()),
                ResumeAutomationSettingsResponse.from(resume.settings()),
                ResumeStatisticsResponse.from(profile.statistics()),
                resume.plainText(), resume.textLoadedAt()
        );
    }
}
