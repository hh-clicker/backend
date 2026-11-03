package org.hhclicker.backend.application.dto;

import org.hhclicker.backend.application.model.ApplicationModels.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApplicationRunResponse(UUID id, long sequence, UUID resumeId, String resumeName, RunStatus status,
                                     Instant createdAt, Instant startedAt, Instant finishedAt, int dailyLimit,
                                     int pauseFromSeconds,
                                     int pauseToSeconds, String coverLetter, boolean skipQuestionnaires,
                                     List<ItemResponse> applications,
                                     org.hhclicker.backend.resume.model.AiLetterSettings aiLetter,
                                     Instant aiLimitResetsAt, boolean aiFallbackConfirmed, RunMode mode) {
    public static ApplicationRunResponse from(Run run) {
        return new ApplicationRunResponse(run.id(), run.sequence(), run.resumeId(), run.resumeName(), run.status(),
                run.createdAt(), run.createdAt(), run.finishedAt(), run.dailyLimit(), run.pauseFromSeconds(), run.pauseToSeconds(),
                run.coverLetter(), run.skipQuestionnaires(), run.applications().stream().map(ItemResponse::from).toList(), run.aiLetter(), run.aiLimitResetsAt(), run.aiFallbackConfirmed(), run.mode());
    }

    public record ItemResponse(UUID id, UUID vacancyId, String hhVacancyId, String vacancyTitle, String companyName,
                               String status, Instant attemptedAt, String message, boolean hasQuestionnaire,
                               Outcome outcome, String attentionStatus,
                               PreparedLetter letter) {
        static ItemResponse from(Item item) {
            return new ItemResponse(item.id(), item.vacancyId(), item.hhVacancyId(), item.vacancyTitle(), item.companyName(),
                    item.status(), item.attemptedAt(), item.message(), item.hasQuestionnaire(), item.outcome(), item.attentionStatus(), item.letter());
        }
    }
}
