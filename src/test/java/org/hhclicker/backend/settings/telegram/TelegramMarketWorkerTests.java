package org.hhclicker.backend.settings.telegram;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.market.MarketAnalysisService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramMarketWorkerTests {
    private static final TelegramMessageService.Target TARGET = new TelegramMessageService.Target(42, 84);
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private final MarketAnalysisService market = mock(MarketAnalysisService.class);
    private final TelegramMessageService telegram = mock(TelegramMessageService.class);
    private final TelegramMarketMessageRepository messages = mock(TelegramMarketMessageRepository.class);
    private final TelegramMarketWorker worker = new TelegramMarketWorker(market, telegram, messages);

    @Test
    void runningAnalysisGetsOneMessageAndProgressEditsIt() {
        var initial = run("RUNNING", 8);
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(market.notificationRuns()).thenReturn(List.of(initial));
        when(telegram.send(eq(TARGET), anyString(), eq(List.of()))).thenReturn(123L);

        worker.synchronize();

        verify(telegram).send(eq(TARGET), contains("8 из 20"), eq(List.of()));
        verify(messages).save(eq(RUN_ID), eq(TARGET), eq(123L), anyString());

        var next = run("RUNNING", 16);
        when(market.notificationRuns()).thenReturn(List.of(next));
        when(messages.findAll(TARGET)).thenReturn(Map.of(RUN_ID,
                new TelegramMarketMessageRepository.Message(123L,
                        TelegramMarketPresentation.fingerprint(TelegramMarketPresentation.text(initial)))));

        worker.synchronize();

        verify(telegram).edit(eq(TARGET), eq(123L), contains("16 из 20"), eq(List.of()));
        verify(messages).updateFingerprint(eq(RUN_ID), eq(TARGET), anyString());
    }

    @Test
    void completionUpdatesExistingMessageButDoesNotReplayOldHistory() {
        var complete = run("COMPLETED", 20);
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(market.notificationRuns()).thenReturn(List.of(complete));

        worker.synchronize();
        verify(telegram, never()).send(any(), anyString(), anyList());

        when(messages.findAll(TARGET)).thenReturn(Map.of(RUN_ID,
                new TelegramMarketMessageRepository.Message(123L, "old")));
        when(telegram.send(eq(TARGET), anyString(), eq(List.of()))).thenReturn(124L);
        worker.synchronize();
        verify(telegram).delete(TARGET, 123L);
        verify(telegram).send(eq(TARGET), contains("завершён"), eq(List.of()));
        verify(messages).save(eq(RUN_ID), eq(TARGET), eq(124L), anyString());
    }

    @Test
    void quickFailureAfterConnectionIsSentEvenWithoutObservedProgress() {
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(market.notificationRuns()).thenReturn(List.of());
        worker.synchronize();

        var failed = new MarketAnalysisService.NotificationRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(),
                "Java", "FAILED", "EXTRACT", "r2", Instant.now().plusSeconds(1), 0, 20, "Нет связи");
        when(market.notificationRuns()).thenReturn(List.of(failed));
        when(telegram.send(eq(TARGET), anyString(), eq(List.of()))).thenReturn(200L);

        worker.synchronize();

        verify(telegram).send(eq(TARGET), contains("Нет связи"), eq(List.of()));
        verify(messages).save(eq(RUN_ID), eq(TARGET), eq(200L), anyString());
    }

    @Test
    void failureShowsReasonWithoutExposingUnlimitedText() {
        String text = TelegramMarketPresentation.text(new MarketAnalysisService.NotificationRun(
                RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Java", "FAILED", "EXTRACT", "r1", Instant.EPOCH,
                8, 20, "x".repeat(1000)));
        assertTrue(text.contains("8 из 20"));
        assertTrue(text.contains("Причина:"));
        assertTrue(text.length() < 650);
    }

    private MarketAnalysisService.NotificationRun run(String status, int processed) {
        return new MarketAnalysisService.NotificationRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Java",
                status, "EXTRACT", "revision", Instant.EPOCH, processed, 20, null);
    }
}
