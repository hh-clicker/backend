package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.agent.exception.InvalidBrowserAgentDataException;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.agent.model.BrowserAgentSnapshot;
import org.hhclicker.backend.agent.service.BrowserAgentStatusService;
import org.hhclicker.backend.support.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Heartbeat Browser Agent и вычисление доступности на границе интервала.
 */
class BrowserAgentStatusServiceTests {

    private static final Instant START = Instant.parse("2026-09-04T09:00:00Z");
    private static final Duration OFFLINE_AFTER = Duration.ofSeconds(15);

    private final MutableClock clock = new MutableClock(START);
    private final BrowserAgentStatusService service = new BrowserAgentStatusService(clock, OFFLINE_AFTER);

    private final UUID profileId = UUID.randomUUID();

    private BrowserAgentSnapshot heartbeat(BrowserAgentRuntimeStatus status, boolean browserOpen, UUID activeProfileId) {
        return service.heartbeat("local-browser-agent", "1.2.3", status, browserOpen, activeProfileId);
    }

    @Test
    void withoutAnyHeartbeatTheAgentIsConsideredOffline() {
        BrowserAgentSnapshot snapshot = service.getStatus();

        assertEquals(BrowserAgentRuntimeStatus.OFFLINE, snapshot.status());
        assertNull(snapshot.agentId());
        assertNull(snapshot.version());
        assertNull(snapshot.lastHeartbeatAt());
        assertNull(snapshot.activeProfileId());
        assertFalse(snapshot.browserOpen());
        assertEquals(15, snapshot.offlineAfterSeconds());
    }

    @Test
    void firstHeartbeatBecomesTheCurrentSnapshot() {
        BrowserAgentSnapshot returned = heartbeat(BrowserAgentRuntimeStatus.READY, true, profileId);

        assertEquals("local-browser-agent", returned.agentId());
        assertEquals("1.2.3", returned.version());
        assertEquals(BrowserAgentRuntimeStatus.READY, returned.status());
        assertEquals(START, returned.lastHeartbeatAt());
        assertTrue(returned.browserOpen());
        assertEquals(profileId, returned.activeProfileId());
        assertEquals(returned, service.getStatus(), "возвращённый snapshot совпадает с сохранённым");
    }

    @Test
    void agentIdentifierAndVersionAreTrimmed() {
        BrowserAgentSnapshot returned = service.heartbeat("  local-browser-agent  ", "  1.2.3  ",
                BrowserAgentRuntimeStatus.CONNECTING, false, null);

        assertEquals("local-browser-agent", returned.agentId());
        assertEquals("1.2.3", returned.version());
        assertEquals(BrowserAgentRuntimeStatus.CONNECTING, returned.status());
    }

    @Test
    void agentIsNotAllowedToReportItselfOffline() {
        assertThrows(InvalidBrowserAgentDataException.class,
                () -> heartbeat(BrowserAgentRuntimeStatus.OFFLINE, false, null));

        assertEquals(BrowserAgentRuntimeStatus.OFFLINE, service.getStatus().status());
        assertNull(service.getStatus().agentId(), "отклонённый heartbeat не сохраняется");
    }

    /**
     * Фиксирует фактический контракт: отсутствующие обязательные поля дают NPE, а не доменную ошибку.
     */
    @Test
    void missingIdentifierOrVersionFailsWithoutADomainError() {
        assertThrows(NullPointerException.class,
                () -> service.heartbeat(null, "1.2.3", BrowserAgentRuntimeStatus.READY, false, null));
        assertThrows(NullPointerException.class,
                () -> service.heartbeat("local-browser-agent", null, BrowserAgentRuntimeStatus.READY, false, null));
    }

    @Test
    void agentStaysOnlineUntilTheOfflineBoundaryIsReached() {
        heartbeat(BrowserAgentRuntimeStatus.READY, true, profileId);

        clock.advanceSeconds(14);
        assertEquals(BrowserAgentRuntimeStatus.READY, service.getStatus().status());

        clock.advanceSeconds(1);
        BrowserAgentSnapshot offline = service.getStatus();
        assertEquals(BrowserAgentRuntimeStatus.OFFLINE, offline.status());
        assertEquals("local-browser-agent", offline.agentId(), "последний известный агент остаётся в снимке");
        assertEquals("1.2.3", offline.version());
        assertEquals(START, offline.lastHeartbeatAt());
        assertFalse(offline.browserOpen(), "браузер офлайн-агента не считается открытым");
        assertNull(offline.activeProfileId());
    }

    @Test
    void everyHeartbeatMovesTheOfflineDeadline() {
        heartbeat(BrowserAgentRuntimeStatus.READY, true, profileId);
        clock.advanceSeconds(14);
        heartbeat(BrowserAgentRuntimeStatus.READY, true, profileId);

        clock.advanceSeconds(14);

        assertEquals(BrowserAgentRuntimeStatus.READY, service.getStatus().status());
        assertEquals(START.plusSeconds(14), service.getStatus().lastHeartbeatAt());
    }

    @Test
    void anotherAgentSimplyReplacesThePreviousSnapshot() {
        heartbeat(BrowserAgentRuntimeStatus.READY, true, profileId);

        service.heartbeat("second-browser-agent", "2.0.0", BrowserAgentRuntimeStatus.BUSY, false, null);

        BrowserAgentSnapshot snapshot = service.getStatus();
        assertEquals("second-browser-agent", snapshot.agentId());
        assertEquals("2.0.0", snapshot.version());
        assertEquals(BrowserAgentRuntimeStatus.BUSY, snapshot.status());
        assertFalse(snapshot.browserOpen());
        assertNull(snapshot.activeProfileId());
    }

    @Test
    void browserStateIsUpdatedSeparatelyFromHeartbeats() {
        heartbeat(BrowserAgentRuntimeStatus.BUSY, false, null);
        clock.advanceSeconds(5);

        service.updateBrowserState(true, profileId);

        BrowserAgentSnapshot snapshot = service.getStatus();
        assertTrue(snapshot.browserOpen());
        assertEquals(profileId, snapshot.activeProfileId());
        assertEquals("local-browser-agent", snapshot.agentId());
        assertEquals(BrowserAgentRuntimeStatus.READY, snapshot.status(),
                "подтверждённое открытие браузера переводит агента в READY");
        assertEquals(START.plusSeconds(5), snapshot.lastHeartbeatAt(),
                "обновление состояния браузера продлевает жизнь снимка");
    }

    @Test
    void closingTheBrowserClearsTheActiveProfile() {
        heartbeat(BrowserAgentRuntimeStatus.READY, true, profileId);

        service.updateBrowserState(false, null);

        assertFalse(service.getStatus().browserOpen());
        assertNull(service.getStatus().activeProfileId());
    }

    @Test
    void browserStateWithoutAnyHeartbeatIsIgnored() {
        service.updateBrowserState(true, profileId);

        assertEquals(BrowserAgentRuntimeStatus.OFFLINE, service.getStatus().status());
        assertFalse(service.getStatus().browserOpen());
    }

    @Test
    void snapshotReflectsTheWholeSequenceOfUpdates() {
        heartbeat(BrowserAgentRuntimeStatus.CONNECTING, false, null);
        clock.advanceSeconds(3);
        service.updateBrowserState(true, profileId);
        clock.advanceSeconds(3);
        UUID anotherProfileId = UUID.randomUUID();
        service.heartbeat("local-browser-agent", "1.2.4", BrowserAgentRuntimeStatus.BUSY, true, anotherProfileId);

        BrowserAgentSnapshot snapshot = service.getStatus();
        assertEquals("1.2.4", snapshot.version());
        assertEquals(BrowserAgentRuntimeStatus.BUSY, snapshot.status());
        assertTrue(snapshot.browserOpen());
        assertEquals(anotherProfileId, snapshot.activeProfileId());
        assertEquals(START.plusSeconds(6), snapshot.lastHeartbeatAt());
    }
}
