package org.hhclicker.backend.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.hhclicker.backend.agent.exception.InvalidBrowserAgentDataException;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.agent.model.BrowserAgentSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BrowserAgentStatusService {

    private final Clock clock;
    private final Duration offlineTimeout;
    private final AtomicReference<Heartbeat> latestHeartbeat = new AtomicReference<>();

    public BrowserAgentStatusService(
            Clock clock,
            @Value("${hh-clicker.browser-agent.offline-timeout:15s}") Duration offlineTimeout
    ) {
        this.clock = clock;
        this.offlineTimeout = offlineTimeout;
    }

    public BrowserAgentSnapshot heartbeat(
            String agentId,
            String version,
            BrowserAgentRuntimeStatus status,
            boolean browserOpen,
            UUID activeProfileId
    ) {
        if (status == BrowserAgentRuntimeStatus.OFFLINE) {
            throw new InvalidBrowserAgentDataException("Browser Agent cannot report OFFLINE status");
        }

        Heartbeat heartbeat = new Heartbeat(
                Objects.requireNonNull(agentId).trim(),
                Objects.requireNonNull(version).trim(),
                Objects.requireNonNull(status),
                clock.instant(),
                browserOpen,
                activeProfileId
        );
        latestHeartbeat.set(heartbeat);
        return toSnapshot(heartbeat);
    }

    public BrowserAgentSnapshot getStatus() {
        Heartbeat heartbeat = latestHeartbeat.get();

        if (heartbeat == null) {
            return BrowserAgentSnapshot.offline(offlineTimeout.toSeconds());
        }

        Instant offlineAt = heartbeat.receivedAt().plus(offlineTimeout);
        if (!clock.instant().isBefore(offlineAt)) {
            return new BrowserAgentSnapshot(
                    heartbeat.agentId(),
                    heartbeat.version(),
                    BrowserAgentRuntimeStatus.OFFLINE,
                    heartbeat.receivedAt(),
                    offlineTimeout.toSeconds(),
                    false,
                    null
            );
        }

        return toSnapshot(heartbeat);
    }

    public void updateBrowserState(boolean browserOpen, UUID activeProfileId) {
        latestHeartbeat.updateAndGet(heartbeat -> heartbeat == null
                ? null
                : new Heartbeat(
                heartbeat.agentId(),
                heartbeat.version(),
                BrowserAgentRuntimeStatus.READY,
                clock.instant(),
                browserOpen,
                activeProfileId
        ));
    }

    private BrowserAgentSnapshot toSnapshot(Heartbeat heartbeat) {
        return new BrowserAgentSnapshot(
                heartbeat.agentId(),
                heartbeat.version(),
                heartbeat.status(),
                heartbeat.receivedAt(),
                offlineTimeout.toSeconds(),
                heartbeat.browserOpen(),
                heartbeat.activeProfileId()
        );
    }

    private record Heartbeat(
            String agentId,
            String version,
            BrowserAgentRuntimeStatus status,
            Instant receivedAt,
            boolean browserOpen,
            UUID activeProfileId
    ) {
    }
}
