package org.hhclicker.backend.application.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.hhclicker.backend.application.model.ApplicationModels.Item;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class TelegramApplicationCompletionWorkerTests {
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final TelegramMessageService.Target TARGET = new TelegramMessageService.Target(42, 84);
    private final ApplicationService applications = mock(ApplicationService.class);
    private final TelegramMessageService telegram = mock(TelegramMessageService.class);
    private final TelegramRunMessageRepository runMessages = mock(TelegramRunMessageRepository.class);
    private final TelegramAttentionMessageRepository attentionMessages = mock(TelegramAttentionMessageRepository.class);
    private final TelegramRunCompletionRepository completions = mock(TelegramRunCompletionRepository.class);
    private final TelegramApplicationCompletionWorker worker = new TelegramApplicationCompletionWorker(
            applications, telegram, runMessages, attentionMessages, completions);

    @Test
    void repeatedDeliveryFailuresProduceOneSafeWarning(CapturedOutput output) {
        Run run = run();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(runMessages.completionCandidates(TARGET)).thenReturn(List.of(RUN_ID));
        when(applications.find(RUN_ID)).thenReturn(run);
        when(telegram.send(eq(TARGET), anyString(), anyList()))
                .thenThrow(new IllegalStateException("private-token-and-message"));
        when(completions.findAll(TARGET)).thenReturn(Map.of());

        worker.synchronize();
        worker.synchronize();

        String logs = output.getAll();
        assertTrue(logs.contains("operation=create"));
        assertTrue(logs.contains(RUN_ID.toString()));
        assertTrue(logs.contains("type=IllegalStateException"));
        assertFalse(logs.contains("private-token-and-message"));
        assertEquals(1L, logs.lines()
                .filter(line -> line.contains("Telegram completion deferred:")).count());
        verify(telegram, times(2)).send(eq(TARGET), anyString(), anyList());
    }

    @Test
    void terminalObservedRunGetsASeparateCompletionMessage() {
        Run run = run();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(runMessages.completionCandidates(TARGET)).thenReturn(List.of(RUN_ID));
        when(applications.find(RUN_ID)).thenReturn(run);
        when(telegram.send(eq(TARGET), anyString(), anyList())).thenReturn(55L);
        when(completions.findAll(TARGET)).thenReturn(Map.of());

        worker.synchronize();

        verify(telegram).send(eq(TARGET), contains("Запуск #7"), anyList());
        verify(completions).save(eq(RUN_ID), eq(TARGET), eq(55L), anyString());
    }

    @Test
    void confirmationDeletesIntermediateMessagesAndLeavesFinalStatistic() {
        Run run = run();
        var completion = new TelegramRunCompletionRepository.Message(55L, "old", true, false);
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(runMessages.completionCandidates(TARGET)).thenReturn(List.of());
        when(completions.findAll(TARGET)).thenReturn(Map.of(RUN_ID, completion));
        when(applications.find(RUN_ID)).thenReturn(run);
        when(attentionMessages.messageIds(RUN_ID, TARGET)).thenReturn(List.of(66L, 77L));
        when(runMessages.find(RUN_ID, TARGET)).thenReturn(Optional.of(
                new TelegramRunMessageRepository.Message(44L, "run")));

        worker.synchronize();

        verify(telegram).delete(TARGET, 66L);
        verify(telegram).delete(TARGET, 77L);
        verify(attentionMessages).closeRun(RUN_ID, TARGET);
        verify(telegram).delete(TARGET, 44L);
        verify(telegram).edit(eq(TARGET), eq(55L), contains("Итог подтверждён"), eq(List.of()));
        verify(completions).update(eq(RUN_ID), eq(TARGET), anyString(), eq(true));
        verify(runMessages).markCompletionHandled(RUN_ID, TARGET);
    }

    private Run run() {
        return run(RUN_ID);
    }

    @Test
    void failedCompletionDoesNotPreventAnotherRunFromReceivingItsSummary() {
        UUID otherId = UUID.randomUUID();
        Run firstRun = run();
        Run otherRun = run(otherId);
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(runMessages.completionCandidates(TARGET)).thenReturn(List.of(RUN_ID, otherId));
        when(applications.find(RUN_ID)).thenReturn(firstRun);
        when(applications.find(otherId)).thenReturn(otherRun);
        when(telegram.send(eq(TARGET), anyString(), anyList()))
                .thenThrow(new IllegalStateException("Synthetic send failure")).thenReturn(55L);
        when(completions.findAll(TARGET)).thenReturn(Map.of());

        worker.synchronize();

        verify(completions, never()).save(eq(RUN_ID), any(), anyLong(), anyString());
        verify(completions).save(eq(otherId), eq(TARGET), eq(55L), anyString());
    }

    private Run run(UUID id) {
        Item item = mock(Item.class);
        when(item.status()).thenReturn("SUCCEEDED");
        Run run = mock(Run.class);
        when(run.id()).thenReturn(id);
        when(run.sequence()).thenReturn(7L);
        when(run.resumeName()).thenReturn("Java");
        when(run.status()).thenReturn(RunStatus.COMPLETED);
        when(run.applications()).thenReturn(List.of(item));
        return run;
    }

    @Test
    void failedCleanupStillDeletesOtherMessagesAndRetriesBeforeMarkingTheRunCleaned() {
        Run completedRun = run();
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(runMessages.completionCandidates(TARGET)).thenReturn(List.of());
        when(completions.findAll(TARGET)).thenReturn(Map.of(RUN_ID,
                new TelegramRunCompletionRepository.Message(55L, "old", true, false)));
        when(applications.find(RUN_ID)).thenReturn(completedRun);
        when(attentionMessages.messageIds(RUN_ID, TARGET)).thenReturn(List.of(66L, 77L));
        when(runMessages.find(RUN_ID, TARGET)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("Synthetic deletion failure")).doNothing()
                .when(telegram).delete(TARGET, 66L);

        worker.synchronize();
        verify(telegram).delete(TARGET, 77L);
        verify(completions, never()).update(any(), any(), anyString(), eq(true));

        worker.synchronize();
        verify(completions).update(eq(RUN_ID), eq(TARGET), anyString(), eq(true));
        verify(runMessages).markCompletionHandled(RUN_ID, TARGET);
    }
}
