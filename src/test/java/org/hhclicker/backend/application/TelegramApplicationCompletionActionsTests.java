package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.Item;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.application.telegram.TelegramApplicationCompletionActions;
import org.hhclicker.backend.application.telegram.TelegramRunCompletionRepository;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TelegramApplicationCompletionActionsTests {
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final TelegramMessageService.Target TARGET = new TelegramMessageService.Target(42, 84);
    private final ApplicationService applications = mock(ApplicationService.class);
    private final TelegramMessageService telegram = mock(TelegramMessageService.class);
    private final TelegramRunCompletionRepository completions = mock(TelegramRunCompletionRepository.class);
    private final TelegramApplicationCompletionActions actions =
            new TelegramApplicationCompletionActions(applications, telegram, completions);

    @Test
    void completedRunWithoutOpenAttentionCanBeConfirmed() {
        Run run = run(List.of(item("ATTENTION_REQUIRED", "RESOLVED")));
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(completions.find(RUN_ID, TARGET)).thenReturn(Optional.of(
                new TelegramRunCompletionRepository.Message(12, "old", false, false)));
        when(applications.find(RUN_ID)).thenReturn(run);

        assertEquals("Итог запуска подтверждён.",
                actions.handle("completion:confirm:" + RUN_ID).orElseThrow());

        verify(completions).confirm(RUN_ID, TARGET);
    }

    @Test
    void openAttentionStillBlocksConfirmation() {
        Run run = run(List.of(item("ATTENTION_REQUIRED", "OPEN")));
        when(telegram.connectedTarget()).thenReturn(Optional.of(TARGET));
        when(completions.find(RUN_ID, TARGET)).thenReturn(Optional.of(
                new TelegramRunCompletionRepository.Message(12, "old", false, false)));
        when(applications.find(RUN_ID)).thenReturn(run);

        assertEquals("Сначала разберите все ситуации, требующие внимания.",
                actions.handle("completion:confirm:" + RUN_ID).orElseThrow());

        verify(completions, never()).confirm(any(), any());
    }

    private Run run(List<Item> items) {
        Run run = mock(Run.class);
        when(run.status()).thenReturn(RunStatus.COMPLETED);
        when(run.applications()).thenReturn(items);
        return run;
    }

    private Item item(String status, String attentionStatus) {
        Item item = mock(Item.class);
        when(item.status()).thenReturn(status);
        when(item.attentionStatus()).thenReturn(attentionStatus);
        return item;
    }
}
