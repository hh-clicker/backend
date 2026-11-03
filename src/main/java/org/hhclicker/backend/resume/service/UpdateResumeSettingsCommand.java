package org.hhclicker.backend.resume.service;

public record UpdateResumeSettingsCommand(
        String coverLetter,
        int dailyLimit,
        int pauseFromSeconds,
        int pauseToSeconds,
        boolean skipQuestionnaires,
        org.hhclicker.backend.resume.model.AiLetterSettings aiLetter
) {
    public UpdateResumeSettingsCommand(String coverLetter, int dailyLimit, int pauseFromSeconds, int pauseToSeconds, boolean skipQuestionnaires) {
        this(coverLetter, dailyLimit, pauseFromSeconds, pauseToSeconds, skipQuestionnaires, null);
    }
}
