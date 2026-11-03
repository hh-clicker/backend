package org.hhclicker.backend.application.telegram;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.RunMode;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramApplicationAttentionWorkerTests {
    private static final UUID ATTENTION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final TelegramMessageService.Target TARGET = new TelegramMessageService.Target(42, 84);
    private final ApplicationService applications = mock(ApplicationService.class);
    private final TelegramMessageService telegram = mock(TelegramMessageService.class);
    private final TelegramAttentionMessageRepository messages = mock(TelegramAttentionMessageRepository.class);
    private final TelegramApplicationAttentionWorker worker =
            new TelegramApplicationAttentionWorker(applications, telegram, messages);

    @Test
    void newOpenAttentionGetsItsOwnMessage() {
        Attention attention = attention("OPEN");
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of());
        when(applications.openAttention()).thenReturn(List.of(attention));
        when(telegram.send(eq(TARGET), anyString(), anyList())).thenReturn(123L);

        worker.synchronize();

        verify(telegram).send(eq(TARGET), contains("Статус: ожидает решения"), anyList());
        verify(messages).save(eq(ATTENTION_ID), eq(TARGET), eq(123L), anyString());
    }

    @Test
    void handledAttentionMessageIsDeletedAndClosed() {
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of(ATTENTION_ID,
                new TelegramAttentionMessageRepository.Message(123L, "old")));
        when(applications.openAttention()).thenReturn(List.of());
        when(applications.attentionTask(ATTENTION_ID)).thenReturn(attention("RESOLVED"));

        worker.synchronize();

        verify(telegram).delete(TARGET, 123L);
        verify(telegram, never()).edit(any(), anyLong(), anyString(), anyList());
        verify(messages).update(eq(ATTENTION_ID), eq(TARGET), anyString(), eq(true));
    }

    @Test
    void failedDeletionLeavesAttentionMessageOpenForRetry() {
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of(ATTENTION_ID,
                new TelegramAttentionMessageRepository.Message(123L, "old")));
        when(applications.openAttention()).thenReturn(List.of());
        when(applications.attentionTask(ATTENTION_ID)).thenReturn(attention("SKIPPED"));
        doThrow(new RuntimeException("Telegram unavailable")).when(telegram).delete(TARGET, 123L);

        assertDoesNotThrow(worker::synchronize);

        verify(messages, never()).update(any(), any(), anyString(), anyBoolean());
    }

    @Test
    void attentionResolvedBeforeItsFirstSyncDoesNotCreateAClosedMessage() {
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of());
        when(applications.openAttention()).thenReturn(List.of(attention("SKIPPED")));

        worker.synchronize();

        verify(telegram, never()).send(any(), anyString(), anyList());
        verify(messages, never()).save(any(), any(), anyLong(), anyString());
    }

    @Test
    void dryRunAttentionDoesNotCreateApplicationTelegramMessage() {
        Attention regular = attention("OPEN");
        Attention dry = new Attention(regular.id(), regular.kind(), regular.status(), regular.title(),
                regular.description(), regular.runId(), regular.runSequence(), regular.applicationId(),
                regular.vacancyId(), regular.hhVacancyId(), regular.vacancyTitle(), regular.employerId(),
                regular.companyName(), regular.resumeName(), regular.occurredAt(), regular.outcome(),
                regular.canContinueRun(), regular.canSkipVacancy(), RunMode.DRY_RUN);
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of());
        when(applications.openAttention()).thenReturn(List.of(dry));

        worker.synchronize();

        verify(telegram, never()).send(any(), anyString(), anyList());
        verify(applications, never()).find(any());
    }

    @Test
    void manuallyDeletedAttentionMessageIsRecreatedEvenWhenItsStateIsUnchanged() {
        Attention attention = attention("OPEN");
        String fingerprint = TelegramAttentionPresentation.from(attention).fingerprint();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of(ATTENTION_ID,
                new TelegramAttentionMessageRepository.Message(123L, fingerprint)));
        when(applications.openAttention()).thenReturn(List.of(attention));
        when(telegram.send(eq(TARGET), anyString(), anyList())).thenReturn(456L);

        worker.synchronize();

        verify(telegram).delete(TARGET, 123L);
        verify(telegram).send(eq(TARGET), contains("Статус: ожидает решения"), anyList());
        verify(messages).save(eq(ATTENTION_ID), eq(TARGET), eq(456L), anyString());
        verify(telegram, never()).edit(any(), anyLong(), anyString(), anyList());
        verify(messages, never()).update(eq(ATTENTION_ID), eq(TARGET), anyString(), anyBoolean());
    }

    private Attention attention(String status) {
        return attention(ATTENTION_ID, status);
    }

    @Test
    void failedMessageDoesNotStarveOtherTasksAndIsRetried() {
        UUID otherId = UUID.randomUUID();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of());
        when(applications.openAttention()).thenReturn(List.of(attention("OPEN"), attention(otherId, "OPEN")));
        when(telegram.send(eq(TARGET), anyString(), anyList()))
                .thenThrow(new IllegalStateException("Synthetic transport failure")).thenReturn(123L);

        assertDoesNotThrow(worker::synchronize);
        verify(messages, never()).save(eq(ATTENTION_ID), any(), anyLong(), anyString());
        verify(messages).save(eq(otherId), eq(TARGET), eq(123L), anyString());

        worker.synchronize();
        verify(messages).save(eq(ATTENTION_ID), eq(TARGET), eq(123L), anyString());
    }

    @Test
    void failedRecreationIsRetriedEvenWhenFingerprintDidNotChange() {
        Attention attention = attention("OPEN");
        String fingerprint = TelegramAttentionPresentation.from(attention).fingerprint();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(messages.findOpen(TARGET)).thenReturn(Map.of(ATTENTION_ID,
                new TelegramAttentionMessageRepository.Message(44L, fingerprint)));
        when(applications.openAttention()).thenReturn(List.of(attention));
        when(telegram.send(eq(TARGET), anyString(), anyList()))
                .thenThrow(new IllegalStateException("Synthetic send failure")).thenReturn(55L);

        worker.synchronize();
        worker.synchronize();

        verify(telegram, times(2)).send(eq(TARGET), anyString(), anyList());
        verify(messages).save(eq(ATTENTION_ID), eq(TARGET), eq(55L), eq(fingerprint));
    }

    private Attention attention(UUID id, String status) {
        return new Attention(id, "ERROR", status, "Проверить", "Описание", UUID.randomUUID(), 7,
                ATTENTION_ID, UUID.randomUUID(), "123", "Java", "employer", "Компания", "Резюме",
                Instant.parse("2026-09-21T09:00:00Z"), Outcome.UNKNOWN, false, true);
    }
}
