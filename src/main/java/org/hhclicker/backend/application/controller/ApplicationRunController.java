package org.hhclicker.backend.application.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.application.dto.ApplicationRunResponse;
import org.hhclicker.backend.application.dto.ApplicationRunSummaryResponse;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.RunMode;
import org.hhclicker.backend.application.model.ApplicationModels.Start;
import org.hhclicker.backend.application.service.ApplicationRunGateway;
import org.hhclicker.backend.application.service.ApplicationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/application-runs")
public class ApplicationRunController {
    private final ApplicationService applications;
    private final ApplicationRunGateway gateway;

    public ApplicationRunController(ApplicationService applications, ApplicationRunGateway gateway) {
        this.applications = applications;
        this.gateway = gateway;
    }

    @GetMapping
    public List<ApplicationRunSummaryResponse> list(@RequestParam UUID resumeId) {
        return applications.summaries(resumeId).stream().map(ApplicationRunSummaryResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ApplicationRunResponse find(@PathVariable UUID id) {
        return ApplicationRunResponse.from(applications.find(id));
    }

    @PostMapping
    public ApplicationRunResponse start(@RequestBody @Valid StartRequest request) {
        return ApplicationRunResponse.from(gateway.startApplications(request.toModel()));
    }

    @PutMapping("/{id}/status")
    public ApplicationRunResponse status(@PathVariable UUID id, @RequestBody @Valid StatusRequest request) {
        return ApplicationRunResponse.from(request.confirmFallback()
                ? gateway.changeApplicationStatus(id, request.status(), true) : gateway.changeApplicationStatus(id, request.status()));
    }

    public record StatusRequest(@NotNull RunStatus status, Boolean confirmFallback) {
        public StatusRequest {
            confirmFallback = Boolean.TRUE.equals(confirmFallback);
        }
        public StatusRequest(RunStatus status) { this(status, false); }
    }

    public record StartRequest(@NotNull UUID requestId, @NotNull UUID resumeId,
                               @NotEmpty @Size(max = 500) List<@NotNull UUID> vacancyIds,
                               @Min(1) @Max(500) int dailyLimit, @Min(10) @Max(3600) int pauseFromSeconds,
                               @Min(10) @Max(3600) int pauseToSeconds, @NotNull @Size(max = 10000) String coverLetter,
                               boolean skipQuestionnaires,
                               org.hhclicker.backend.resume.model.AiLetterSettings aiLetter, RunMode mode) {
        public StartRequest(@NotNull UUID requestId, @NotNull UUID resumeId, List<UUID> vacancyIds,
                            int dailyLimit, int pauseFromSeconds, int pauseToSeconds, String coverLetter,
                            boolean skipQuestionnaires,
                            org.hhclicker.backend.resume.model.AiLetterSettings aiLetter) {
            this(requestId, resumeId, vacancyIds, dailyLimit, pauseFromSeconds, pauseToSeconds,
                    coverLetter, skipQuestionnaires, aiLetter, RunMode.APPLY);
        }
        Start toModel() {
            return new Start(requestId, resumeId, vacancyIds, dailyLimit, pauseFromSeconds,
                    pauseToSeconds, coverLetter, skipQuestionnaires, aiLetter, mode);
        }
    }
}
