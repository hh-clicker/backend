package org.hhclicker.backend.agent.service;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class GenerationStatusService {
    private final Clock clock;
    private final BrowserAgentStatusService browser;
    private UUID requested = UUID.randomUUID();
    private Report latest;
    private String reportingAgent;
    private Instant checkedAt;
    private Instant refreshRequestedAt;
    public GenerationStatusService(Clock clock, BrowserAgentStatusService browser) {
        this.clock = clock;
        this.browser = browser;
    }

    public synchronized UUID requestId() {
        return requested;
    }

    public synchronized void accept(String agentId, Report report) {
        if (report == null || report.state() == State.UNKNOWN || report.state() == State.STALE || report.state() == State.AGENT_OFFLINE)
            return;
        if (!Objects.equals(browser.getStatus().agentId(), agentId.trim())) return;
        // Repeated heartbeats must not make an old probe appear freshly checked.
        if (latest != null && latest.checkId().equals(report.checkId()) && Objects.equals(reportingAgent, agentId.trim()))
            return;
        latest = report;
        reportingAgent = agentId.trim();
        checkedAt = clock.instant().minusMillis(report.ageMs());
    }

    public synchronized View refresh() {
        // Coalesce clicks/multiple tabs while a probe is pending, but allow retry after a lost probe.
        if (refreshRequestedAt == null || !clock.instant().isBefore(refreshRequestedAt.plusSeconds(30))
                || (latest != null && requested.equals(latest.requestId()))) {
            requested = UUID.randomUUID();
            refreshRequestedAt = clock.instant();
        }
        return view();
    }

    public synchronized View view() {
        var agent = browser.getStatus();
        if (agent.status() == BrowserAgentRuntimeStatus.OFFLINE)
            return new View(State.AGENT_OFFLINE, null, false, null, null, null, null, null);
        if (latest == null || !Objects.equals(reportingAgent, agent.agentId()))
            return new View(State.UNKNOWN, null, true, null, null, null, null, null);
        boolean stale = !clock.instant().isBefore(checkedAt.plusSeconds(60));
        boolean pending = !requested.equals(latest.requestId());
        return new View(stale ? State.STALE : latest.state(), checkedAt, pending, latest.model(), latest.reasoningEffort(), latest.cliVersion(), latest.queued(), latest.running(), latest.limits());
    }

    public enum State {READY, AUTH_REQUIRED, UNAVAILABLE, CHECK_FAILED, AGENT_OFFLINE, UNKNOWN, STALE}

    public record Report(@NotNull UUID checkId, @NotNull UUID requestId, @NotNull State state,
                         @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(60001) long ageMs,
                         @Size(max = 100) String model, @Size(max = 100) String reasoningEffort,
                         @Size(max = 100) String cliVersion, @PositiveOrZero Integer queued,
                         @PositiveOrZero Integer running, @jakarta.validation.Valid Limits limits) {
        public Report(UUID checkId, UUID requestId, State state, long ageMs, String model, String reasoningEffort,
                      String cliVersion, Integer queued, Integer running) {
            this(checkId, requestId, state, ageMs, model, reasoningEffort, cliVersion, queued, running, null);
        }
    }

    public record View(State state, Instant checkedAt, boolean refreshing, String model, String reasoningEffort,
                       String cliVersion, Integer queued, Integer running, Limits limits) {
        public View(State state, Instant checkedAt, boolean refreshing, String model, String reasoningEffort,
                    String cliVersion, Integer queued, Integer running) {
            this(state, checkedAt, refreshing, model, reasoningEffort, cliVersion, queued, running, null);
        }
    }
    public record Limits(@jakarta.validation.Valid LimitWindow primary, @jakarta.validation.Valid LimitWindow secondary, Instant checkedAt) {
        public Limits(LimitWindow primary, LimitWindow secondary) { this(primary, secondary, null); }
    }
    public record LimitWindow(@jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("100") double usedPercent,
                              @jakarta.validation.constraints.Positive long windowDurationMins, @PositiveOrZero long resetsAt) {}
}
