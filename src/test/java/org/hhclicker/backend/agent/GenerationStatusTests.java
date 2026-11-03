package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.agent.model.BrowserAgentSnapshot;
import org.hhclicker.backend.agent.service.BrowserAgentStatusService;
import org.hhclicker.backend.agent.service.GenerationStatusService;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationStatusTests {
    private final Clock clock = mock(Clock.class);
    private final BrowserAgentStatusService browser = mock(BrowserAgentStatusService.class);
    private final GenerationStatusService service = new GenerationStatusService(clock, browser);
    private final Instant now = Instant.parse("2026-09-03T12:00:00Z");

    private void online() {
        when(clock.instant()).thenReturn(now);
        when(browser.getStatus()).thenReturn(new BrowserAgentSnapshot("agent", "test", BrowserAgentRuntimeStatus.READY, now, 15, false, null));
    }

    @Test
    void limitWindowsSurviveReportProjectionButDisappearAfterAuthFailure() {
        online();
        var limits = new GenerationStatusService.Limits(new GenerationStatusService.LimitWindow(36, 300, 1789464317L), null);
        service.accept("agent", new GenerationStatusService.Report(UUID.randomUUID(), service.requestId(),
                GenerationStatusService.State.READY, 0, "model", "low", "0.153.0", 0, 0, limits));
        assertEquals(limits, service.view().limits());
        service.accept("agent", new GenerationStatusService.Report(UUID.randomUUID(), service.requestId(),
                GenerationStatusService.State.AUTH_REQUIRED, 0, "model", "low", "0.153.0", 0, 0));
        assertNull(service.view().limits());
    }

    private GenerationStatusService.Report report(UUID requestId, long ageMs) {
        return new GenerationStatusService.Report(UUID.randomUUID(), requestId, GenerationStatusService.State.READY,
                ageMs, "gpt-5.6-luna", "low", "0.153.0", 0, 0);
    }

    @Test
    void repeatedHeartbeatCannotRefreshOldEvidence() {
        online();
        var report = report(service.requestId(), 0);
        service.accept("agent", report);
        assertEquals(GenerationStatusService.State.READY, service.view().state());
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        var changedDuplicate = new GenerationStatusService.Report(report.checkId(), report.requestId(),
                GenerationStatusService.State.AUTH_REQUIRED, 0, "different-model", "high", "9.9.9", 7, 8);
        clearInvocations(browser);
        service.accept("agent", changedDuplicate);
        verify(browser).getStatus();
        assertEquals(now, service.view().checkedAt());
        assertEquals(GenerationStatusService.State.STALE, service.view().state());
        assertEquals("gpt-5.6-luna", service.view().model(),
                "дубликат checkId не должен заменить сохранённый snapshot");
    }

    @Test
    void staleCachedReportAfterBackendRestartIsNotReady() {
        online();
        service.accept("agent", report(service.requestId(), 60001));
        assertEquals(GenerationStatusService.State.STALE, service.view().state());
    }

    @Test
    void refreshIsCoalescedAndRequiresMatchingResponse() {
        online();
        var previous = service.requestId();
        service.refresh();
        var requested = service.requestId();
        service.refresh();
        assertEquals(requested, service.requestId());
        service.accept("agent", report(previous, 0));
        assertTrue(service.view().refreshing());
        service.accept("agent", report(requested, 0));
        assertFalse(service.view().refreshing());
    }

    @Test
    void browserOfflineOrDifferentAgentCannotShowCachedReady() {
        online();
        service.accept("agent", report(service.requestId(), 0));
        when(browser.getStatus()).thenReturn(BrowserAgentSnapshot.offline(15));
        assertEquals(GenerationStatusService.State.AGENT_OFFLINE, service.view().state());
        assertNull(service.view().checkedAt());
        when(browser.getStatus()).thenReturn(new BrowserAgentSnapshot("other", "test", BrowserAgentRuntimeStatus.READY, now, 15, false, null));
        assertEquals(GenerationStatusService.State.UNKNOWN, service.view().state());
    }
}
