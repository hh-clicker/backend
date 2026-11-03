package org.hhclicker.backend.settings.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.model.NotificationSettings;
import org.hhclicker.backend.settings.service.NotificationService;
import org.hhclicker.backend.vacancy.relevance.VacancyRelevanceNotificationRun;
import org.hhclicker.backend.vacancy.relevance.VacancyRelevanceService;
import org.hhclicker.backend.vacancy.relevance.ParallelVacancyRelevanceService;
import org.hhclicker.backend.market.MarketAnalysisService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final NotificationService notifications;
    private final VacancyRelevanceService relevance;
    private final MarketAnalysisService market;
    private final ParallelVacancyRelevanceService parallelRelevance;

    public SettingsController(NotificationService notifications, VacancyRelevanceService relevance, MarketAnalysisService market,
                              ParallelVacancyRelevanceService parallelRelevance) {
        this.notifications = notifications;
        this.relevance = relevance;
        this.market = market;
        this.parallelRelevance = parallelRelevance;
    }

    @GetMapping
    public Response get() {
        return new Response(new Settings(notifications.settings()));
    }

    @PutMapping
    public Response save(@RequestBody @Valid Settings settings) {
        return new Response(new Settings(notifications.save(settings.notifications())));
    }

    @GetMapping("/notification-state")
    public State state() {
        var relevanceRuns = java.util.stream.Stream.concat(relevance.notificationRuns().stream(),
                parallelRelevance.notificationRuns().stream()).toList();
        return new State(notifications.settings(), notifications.runs(), relevanceRuns, market.notificationRuns());
    }

    public record Settings(@NotNull @Valid NotificationSettings notifications) {
    }

    public record Response(Settings settings) {
    }

    public record State(NotificationSettings settings, List<NotificationRun> runs,
                        List<VacancyRelevanceNotificationRun> relevanceRuns,
                        List<MarketAnalysisService.NotificationRun> marketAnalyses) {
    }
}
