package org.hhclicker.backend.overview.dto;

import org.hhclicker.backend.overview.service.OverviewData;
import org.hhclicker.backend.overview.service.OverviewMetric;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OverviewResponse(
        List<SelectionOption> accounts,
        UUID selectedAccountId,
        List<SelectionOption> resumes,
        UUID selectedResumeId,
        String agentStatus,
        String authorizationStatus,
        ActiveRunSummary activeRun,
        List<Metric> metrics,
        List<RecentSearch> recentSearches,
        List<AttentionItem> attentionItems
) {

    public static OverviewResponse from(OverviewData data) {
        return new OverviewResponse(
                data.accounts().stream()
                        .map(account -> new SelectionOption(account.id(), account.name()))
                        .toList(),
                data.selectedAccount() == null ? null : data.selectedAccount().id(),
                data.resumes().stream()
                        .map(profile -> new SelectionOption(profile.resume().id(), profile.resume().name()))
                        .toList(),
                data.selectedResume() == null ? null : data.selectedResume().resume().id(),
                "OFFLINE",
                data.selectedAccount() == null ? "UNKNOWN" : data.selectedAccount().authorizationStatus().name(),
                data.applicationRuns().stream().filter(run -> run.status() != org.hhclicker.backend.application.model.ApplicationModels.RunStatus.COMPLETED
                                && run.status() != org.hhclicker.backend.application.model.ApplicationModels.RunStatus.CANCELLED)
                        .findFirst().map(OverviewResponse::activeRun).orElse(null),
                data.metrics().stream().map(Metric::from).toList(),
                data.recentSearches().stream()
                        .map(search -> new RecentSearch(
                                search.id(), search.name(), search.createdAt(), search.newVacancies(), search.status()
                        ))
                        .toList(),
                data.attentionTasks().stream().limit(5).map(task -> new AttentionItem(task.id(), task.vacancyTitle(),
                        task.companyName(), task.kind(), task.occurredAt() == null ? null : task.occurredAt().toString())).toList()
        );
    }

    private static ActiveRunSummary activeRun(org.hhclicker.backend.application.model.ApplicationModels.Run run) {
        var items = run.applications();
        return new ActiveRunSummary(run.id(), run.sequence(), run.status().name(),
                items.stream().filter(i -> !List.of("QUEUED", "IN_PROGRESS").contains(i.status())).count(), items.size(),
                items.stream().filter(i -> i.status().equals("SUCCEEDED")).count(),
                items.stream().filter(i -> i.status().equals("ATTENTION_REQUIRED") && i.attentionStatus().equals("OPEN")).count(),
                items.stream().filter(i -> i.status().equals("FAILED")).count());
    }

    public record SelectionOption(UUID id, String name) {
    }

    public record Metric(String id, String label, long value, String hint, String tone) {

        static Metric from(OverviewMetric metric) {
            return new Metric(metric.id(), metric.label(), metric.value(), metric.hint(), metric.tone());
        }
    }

    public record ActiveRunSummary(
            UUID id,
            long sequence,
            String status,
            long processed,
            long total,
            long succeeded,
            long attention,
            long failed
    ) {
    }

    public record RecentSearch(
            UUID id,
            String name,
            Instant createdAt,
            long newVacancies,
            String status
    ) {
    }

    public record AttentionItem(
            UUID id,
            String vacancy,
            String company,
            String kind,
            String occurredAt
    ) {
    }
}
