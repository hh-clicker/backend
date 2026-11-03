package org.hhclicker.backend.application.telegram;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.Item;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.RunMode;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramRunCompletionPresentationTests {
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void openAttentionPreventsConfirmation() {
        var presentation = TelegramRunCompletionPresentation.from(run(List.of(
                item("SUCCEEDED", "OPEN"), item("ATTENTION_REQUIRED", "OPEN"))), false);

        assertTrue(presentation.text().contains("есть необработанные ситуации: 1"));
        assertTrue(presentation.buttons().isEmpty());
    }

    @Test
    void resolvedAttentionEnablesConfirmation() {
        var presentation = TelegramRunCompletionPresentation.from(run(List.of(
                item("SUCCEEDED", "OPEN"), item("ATTENTION_REQUIRED", "RESOLVED"))), false);

        assertTrue(presentation.text().contains("Все ситуации разобраны"));
        assertEquals("completion:confirm:" + RUN_ID, presentation.buttons().getFirst().data());
    }

    @Test
    void confirmedResultIsOnlyAStatisticWithoutButtons() {
        var presentation = TelegramRunCompletionPresentation.from(run(List.of(item("SUCCEEDED", "OPEN"))), true);

        assertTrue(presentation.text().contains("Итог подтверждён"));
        assertTrue(presentation.buttons().isEmpty());
    }

    @Test
    void inProgressItemPreventsPrematureConfirmationAfterCancellation() {
        var presentation = TelegramRunCompletionPresentation.from(run(List.of(item("IN_PROGRESS", "OPEN"))), false);

        assertTrue(presentation.text().contains("Ожидаем завершения уже начатой операции"));
        assertTrue(presentation.buttons().isEmpty());
    }

    @Test
    void dryRunResultDescribesTextLoadingWithoutApplicationStatistics() {
        Run run = run(List.of(item("SUCCEEDED", "OPEN"), item("FAILED", "OPEN")));
        when(run.mode()).thenReturn(RunMode.DRY_RUN);

        var presentation = TelegramRunCompletionPresentation.from(run, false);

        assertTrue(presentation.text().contains("Холостой запуск #7"));
        assertTrue(presentation.text().contains("Текст загружен: 1"));
        assertTrue(presentation.text().contains("Не удалось загрузить: 1"));
        assertFalse(presentation.text().contains("Успешно:"));
        assertEquals("completion:confirm:" + RUN_ID, presentation.buttons().getFirst().data());
    }

    private Run run(List<Item> items) {
        Run run = mock(Run.class);
        when(run.id()).thenReturn(RUN_ID);
        when(run.sequence()).thenReturn(7L);
        when(run.resumeName()).thenReturn("Java");
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
