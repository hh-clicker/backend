package org.hhclicker.backend.application.telegram;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.settings.model.NotificationRun;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelegramRunPresentationTests {
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void runningRunShowsProgressAndPauseButton() {
        var presentation = TelegramRunPresentation.from(run("RUNNING"));

        assertTrue(presentation.text().contains("Запуск #7"));
        assertTrue(presentation.text().startsWith("🚀"));
        assertTrue(presentation.text().contains("Обработано: 5 из 10"));
        assertTrue(presentation.text().contains("Требуют внимания: 2"));
        assertTrue(presentation.text().contains("Примерно осталось: 5 мин"));
        assertEquals("⏸ Пауза", presentation.buttons().getFirst().text());
        assertEquals("application:pause:" + RUN_ID, presentation.buttons().getFirst().data());
    }

    @Test
    void pausedRunOffersResumeAndCompletedRunRemovesButtons() {
        var paused = TelegramRunPresentation.from(run("PAUSED_ERROR"));
        assertTrue(paused.text().startsWith("⚠️"));
        assertTrue(paused.text().contains("Статус: приостановлен из-за ошибки"));
        assertTrue(paused.text().contains("После продолжения: примерно 5 мин"));
        assertEquals("▶ Продолжить", paused.buttons().getFirst().text());

        var completed = TelegramRunPresentation.from(run("COMPLETED"));
        assertTrue(completed.text().startsWith("✅"));
        assertTrue(completed.buttons().isEmpty());
    }

    @Test
    void cancelledRunUsesTerminalIconAndCallsRemainderUnprocessed() {
        var cancelled = TelegramRunPresentation.from(run("CANCELLED"));

        assertTrue(cancelled.text().startsWith("🚫"));
        assertTrue(cancelled.text().contains("Статус: отменён"));
        assertTrue(cancelled.text().contains("Не обработано: 5"));
        assertFalse(cancelled.text().contains("Осталось:"));
        assertTrue(cancelled.buttons().isEmpty());
    }

    @Test
    void processedCountIncludesReviewedAttentionAndCancelledItemWithoutAnExtraLine() {
        var run = new NotificationRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Java", 7,
                "PAUSED_USER", "revision", 86, 59, 0, 73, 730, 73, List.of());

        var presentation = TelegramRunPresentation.from(run);

        assertTrue(presentation.text().contains("Обработано: 73 из 86"));
        assertTrue(presentation.text().contains("Осталось: 13"));
        assertFalse(presentation.text().contains("Разобрано вручную"));
    }

    @Test
    void estimateIsHiddenUntilAProcessedItemHasActiveTiming() {
        var run = new NotificationRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Java", 7,
                "RUNNING", "revision", 10, 0, 0, 0, 45, 0, List.of());

        var presentation = TelegramRunPresentation.from(run);

        assertFalse(presentation.text().contains("Примерно осталось"));
    }

    @Test
    void dryRunShowsTextLoadingProgressAndControls() {
        var run = new NotificationRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Java", 7,
                "RUNNING", "revision", 10, 3, 1, 5, 300, 5, List.of(), "DRY_RUN");

        var presentation = TelegramRunPresentation.from(run);

        assertTrue(presentation.text().contains("Холостой запуск #7"));
        assertTrue(presentation.text().contains("Текст загружен: 3"));
        assertTrue(presentation.text().contains("Не удалось загрузить: 1"));
        assertFalse(presentation.text().contains("Успешно:"));
        assertEquals("⏸ Пауза", presentation.buttons().getFirst().text());
    }

    private NotificationRun run(String status) {
        return new NotificationRun(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), "Java", 7, status, "revision",
                10, 3, 0, 5, 300, 5, List.of("one", "two"));
    }
}
