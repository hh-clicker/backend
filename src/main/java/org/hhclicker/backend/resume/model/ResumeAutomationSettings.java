package org.hhclicker.backend.resume.model;

public record ResumeAutomationSettings(
        String coverLetter,
        int dailyLimit,
        int pauseFromSeconds,
        int pauseToSeconds,
        boolean skipQuestionnaires,
        AiLetterSettings aiLetter
) {
    public ResumeAutomationSettings {
        aiLetter = aiLetter == null ? AiLetterSettings.defaults() : aiLetter;
    }

    public ResumeAutomationSettings(String coverLetter, int dailyLimit, int pauseFromSeconds, int pauseToSeconds, boolean skipQuestionnaires) {
        this(coverLetter, dailyLimit, pauseFromSeconds, pauseToSeconds, skipQuestionnaires, AiLetterSettings.defaults());
    }

    public static ResumeAutomationSettings defaults() {
        return new ResumeAutomationSettings("", 20, 30, 90, true);
    }
}
