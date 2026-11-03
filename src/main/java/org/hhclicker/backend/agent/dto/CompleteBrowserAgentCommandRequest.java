package org.hhclicker.backend.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;

import java.util.List;

public record CompleteBrowserAgentCommandRequest(
        @NotBlank
        @Size(max = 100)
        String agentId,

        @NotNull
        BrowserAgentCommandResultStatus status,

        @Size(max = 1000)
        String errorMessage,

        @Valid BrowserAuthorizationResultRequest authorization,
        @Size(max = 100) List<@NotNull @Valid BrowserResumeSummaryRequest> resumes,
        @Size(max = 2000) List<@NotNull @Valid BrowserSearchVacancyRequest> vacancies,
        org.hhclicker.backend.application.model.ApplicationModels.Outcome application,
        org.hhclicker.backend.application.model.ApplicationModels.QueueBlocker applicationBlocker,
        @Size(max = 100000) String vacancyPlainText
) {
}
