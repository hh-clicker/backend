package org.hhclicker.backend.resume.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateResumeSettingsRequest(
        @NotNull
        String coverLetter,

        @Min(1)
        @Max(500)
        int dailyLimit,

        @Min(10)
        int pauseFromSeconds,

        @Min(10)
        int pauseToSeconds,

        boolean skipQuestionnaires,
        org.hhclicker.backend.resume.model.AiLetterSettings aiLetter
) {
}
