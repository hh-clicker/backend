package org.hhclicker.backend.resume.dto;

import org.hhclicker.backend.resume.model.ResumeAutomationSettings;

public record ResumeAutomationSettingsResponse(
        String coverLetter,
        int dailyLimit,
        int pauseFromSeconds,
        int pauseToSeconds,
        boolean skipQuestionnaires,
        org.hhclicker.backend.resume.model.AiLetterSettings aiLetter
) {

    static ResumeAutomationSettingsResponse from(ResumeAutomationSettings settings) {
        return new ResumeAutomationSettingsResponse(
                settings.coverLetter(),
                settings.dailyLimit(),
                settings.pauseFromSeconds(),
                settings.pauseToSeconds(),
                settings.skipQuestionnaires(), settings.aiLetter()
        );
    }
}
