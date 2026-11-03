package org.hhclicker.backend.application.telegram;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.service.NotificationService;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramApplicationRunWorkerTests {
    private static final TelegramMessageService.Target TARGET = new TelegramMessageService.Target(42, 84);
    private static final UUID OLD_RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTIVE_RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private final NotificationService notifications = mock(NotificationService.class);
    private final TelegramMessageService telegram = mock(TelegramMessageService.class);
    private final TelegramRunMessageRepository messages = mock(TelegramRunMessageRepository.class);
    private final TelegramApplicationRunWorker worker =
            new TelegramApplicationRunWorker(notifications, telegram, messages);

    @Test
    void brokenOldMessageDoesNotBlockTheActiveRun() {
        NotificationRun old = run(OLD_RUN_ID, 1, "COMPLETED");
        NotificationRun active = run(ACTIVE_RUN_ID, 2, "RUNNING");
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findAll(TARGET)).thenReturn(Map.of(OLD_RUN_ID,
                new TelegramRunMessageRepository.Message(10L, "obsolete")));
        when(notifications.runs()).thenReturn(List.of(old, active));
        doThrow(new RuntimeException("message not found"))
                .when(telegram).edit(eq(TARGET), eq(10L), anyString(), anyList());
        when(telegram.send(eq(TARGET), anyString(), anyList())).thenReturn(20L);

        worker.synchronize();

        verify(telegram).send(eq(TARGET), contains("Запуск #2"), anyList());
        verify(messages).save(eq(ACTIVE_RUN_ID), eq(TARGET), eq(20L), anyString());
    }

    @Test
    void manuallyDeletedRunMessageIsRecreatedEvenWhenItsStateIsUnchanged() {
        NotificationRun active = run(ACTIVE_RUN_ID, 2, "RUNNING");
        String fingerprint = TelegramRunPresentation.from(active).fingerprint();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findAll(TARGET)).thenReturn(Map.of(ACTIVE_RUN_ID,
                new TelegramRunMessageRepository.Message(10L, fingerprint)));
        when(notifications.runs()).thenReturn(List.of(active));
        when(telegram.send(eq(TARGET), anyString(), anyList())).thenReturn(20L);

        worker.synchronize();

        verify(telegram).delete(TARGET, 10L);
        verify(telegram).send(eq(TARGET), contains("Запуск #2"), anyList());
        verify(messages).save(eq(ACTIVE_RUN_ID), eq(TARGET), eq(20L), anyString());
        verify(telegram, never()).edit(any(), anyLong(), anyString(), anyList());
        verify(messages, never()).updateFingerprint(eq(ACTIVE_RUN_ID), eq(TARGET), anyString());
    }

    @Test
    void unchangedRunMessageIsNotVerifiedOnEveryPollingIteration() {
        NotificationRun active = run(ACTIVE_RUN_ID, 2, "RUNNING");
        String fingerprint = TelegramRunPresentation.from(active).fingerprint();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findAll(TARGET)).thenReturn(Map.of(ACTIVE_RUN_ID,
                new TelegramRunMessageRepository.Message(10L, fingerprint)));
        when(notifications.runs()).thenReturn(List.of(active));

        worker.synchronize();
        worker.synchronize();

        verify(telegram, times(1)).delete(TARGET, 10L);
        verify(telegram, times(1)).send(eq(TARGET), anyString(), anyList());
    }

    private NotificationRun run(UUID id, long sequence, String status) {
        return new NotificationRun(id, UUID.randomUUID(), UUID.randomUUID(), "Java", sequence, status, "revision",
                10, 3, 0, 5, 300, 5, List.of());
    }
}
