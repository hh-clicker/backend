package org.hhclicker.backend.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.agent.model.BrowserAgentRuntimeStatus;
import org.hhclicker.backend.agent.model.BrowserAgentSnapshot;
import org.hhclicker.backend.agent.service.BrowserAgentStatusService;
import org.hhclicker.backend.agent.service.GenerationStatusService;
import org.hhclicker.backend.support.MutableClock;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Дополняет {@code GenerationStatusTests} ветвями, которых там нет: игнорируемые отчёты,
 * прокидывание состояний и деталей пробы, повторный refresh после истечения окна склейки.
 */
class GenerationStatusReportTests {

    private static final Instant START = Instant.parse("2026-09-04T09:00:00Z");
    private static final String AGENT_ID = "local-browser-agent";

    private final MutableClock clock = new MutableClock(START);
    private final BrowserAgentStatusService browser = mock(BrowserAgentStatusService.class);
    private final GenerationStatusService service = new GenerationStatusService(clock, browser);

    @BeforeEach
    void agentIsOnline() {
        when(browser.getStatus()).thenReturn(new BrowserAgentSnapshot(AGENT_ID, "1.2.3",
                BrowserAgentRuntimeStatus.READY, START, 15, false, null));
    }

    private GenerationStatusService.Report report(GenerationStatusService.State state, long ageMs) {
        return new GenerationStatusService.Report(UUID.randomUUID(), service.requestId(), state, ageMs,
                "gpt-5.6-luna", "low", "0.153.0", 2, 1);
    }

    @Test
    void requestIdentifierIsStableUntilSomeoneAsksForARefresh() {
        UUID first = service.requestId();

        assertEquals(first, service.requestId());
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatusService.State.class, names = {"UNKNOWN", "STALE", "AGENT_OFFLINE"})
    void reportsWithoutUsefulEvidenceAreIgnored(GenerationStatusService.State state) {
        service.accept(AGENT_ID, report(state, 0));

        GenerationStatusService.View view = service.view();
        assertEquals(GenerationStatusService.State.UNKNOWN, view.state());
        assertNull(view.checkedAt());
        assertTrue(view.refreshing(), "без принятой пробы состояние остаётся неизвестным");
    }

    @Test
    void missingReportIsIgnoredWithoutAnError() {
        service.accept(AGENT_ID, null);

        assertEquals(GenerationStatusService.State.UNKNOWN, service.view().state());
    }

    @Test
    void reportOfAnAgentThatIsNotTheCurrentOneIsIgnored() {
        service.accept("second-browser-agent", report(GenerationStatusService.State.READY, 0));

        assertEquals(GenerationStatusService.State.UNKNOWN, service.view().state());
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatusService.State.class,
            names = {"READY", "AUTH_REQUIRED", "UNAVAILABLE", "CHECK_FAILED"})
    void stateOfTheLastProbeIsExposedAsIs(GenerationStatusService.State state) {
        service.accept(AGENT_ID, report(state, 0));

        assertEquals(state, service.view().state());
    }

    @Test
    void probeDetailsAreExposedTogetherWithItsState() {
        service.accept(AGENT_ID, report(GenerationStatusService.State.READY, 5_000));

        GenerationStatusService.View view = service.view();
        assertEquals("gpt-5.6-luna", view.model());
        assertEquals("low", view.reasoningEffort());
        assertEquals("0.153.0", view.cliVersion());
        assertEquals(2, view.queued());
        assertEquals(1, view.running());
        assertEquals(START.minusSeconds(5), view.checkedAt(),
                "возраст пробы сдвигает момент проверки назад");
    }

    @Test
    void refreshIsRetriedOnceTheCoalescingWindowExpires() {
        service.refresh();
        UUID firstRequest = service.requestId();

        service.refresh();
        assertEquals(firstRequest, service.requestId(), "клики внутри окна склеиваются");

        clock.advanceSeconds(30);
        service.refresh();

        assertNotEquals(firstRequest, service.requestId(), "потерянную пробу можно запросить заново");
    }

    @Test
    void matchingLatestResponseAllowsAnImmediateNewRefreshRequest() {
        service.refresh();
        UUID answeredRequest = service.requestId();
        service.accept(AGENT_ID, report(GenerationStatusService.State.READY, 0));
        assertFalse(service.view().refreshing());

        service.refresh();

        assertNotEquals(answeredRequest, service.requestId(),
                "ответ на текущий requestId снимает coalescing до истечения 30 секунд");
        assertTrue(service.view().refreshing());
    }

    @Test
    void sameCheckIdentifierFromAnotherCurrentAgentIsAcceptedAsNewEvidence() {
        UUID checkId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        UUID requestId = service.requestId();
        service.accept(AGENT_ID, new GenerationStatusService.Report(checkId, requestId,
                GenerationStatusService.State.READY, 0, "first-model", "low", "1.0", 1, 0));
        clock.advanceSeconds(5);
        String secondAgent = "second-browser-agent";
        when(browser.getStatus()).thenReturn(new BrowserAgentSnapshot(secondAgent, "2.0.0",
                BrowserAgentRuntimeStatus.READY, clock.instant(), 15, false, null));
        clearInvocations(browser);

        service.accept(secondAgent, new GenerationStatusService.Report(checkId, requestId,
                GenerationStatusService.State.CHECK_FAILED, 1_000, "second-model", "high", "2.0", 3, 2));

        verify(browser).getStatus();
        GenerationStatusService.View view = service.view();
        assertEquals(GenerationStatusService.State.CHECK_FAILED, view.state());
        assertEquals("second-model", view.model());
        assertEquals("high", view.reasoningEffort());
        assertEquals("2.0", view.cliVersion());
        assertEquals(3, view.queued());
        assertEquals(2, view.running());
        assertEquals(START.plusSeconds(4), view.checkedAt());
    }

    @Test
    void answerOfAnOutdatedRequestKeepsTheViewRefreshing() {
        UUID outdated = service.requestId();
        service.refresh();

        service.accept(AGENT_ID, new GenerationStatusService.Report(UUID.randomUUID(), outdated,
                GenerationStatusService.State.READY, 0, null, null, null, null, null));

        GenerationStatusService.View view = service.view();
        assertEquals(GenerationStatusService.State.READY, view.state(),
                "последний известный результат остаётся видимым");
        assertTrue(view.refreshing(), "но ответ на устаревший запрос не снимает ожидание");
    }

    @Test
    void reportWithoutOptionalDetailsIsStillAccepted() {
        service.accept(AGENT_ID, new GenerationStatusService.Report(UUID.randomUUID(), service.requestId(),
                GenerationStatusService.State.UNAVAILABLE, 0, null, null, null, null, null));

        GenerationStatusService.View view = service.view();
        assertEquals(GenerationStatusService.State.UNAVAILABLE, view.state());
        assertNull(view.model());
        assertNull(view.queued());
        assertEquals(START, view.checkedAt());
    }
}
