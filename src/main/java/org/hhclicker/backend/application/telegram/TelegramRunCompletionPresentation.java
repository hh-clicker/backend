package org.hhclicker.backend.application.telegram;

import org.hhclicker.backend.application.model.ApplicationModels.Item;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.RunMode;
import org.hhclicker.backend.settings.telegram.TelegramBotApi;

import java.util.List;
import java.util.Objects;

final class TelegramRunCompletionPresentation {
    private TelegramRunCompletionPresentation() {
    }

    static Presentation from(Run run, boolean confirmed) {
        long succeeded = count(run, "SUCCEEDED");
        long attention = count(run, "ATTENTION_REQUIRED");
        long openAttention = run.applications().stream()
                .filter(item -> item.status().equals("ATTENTION_REQUIRED") && item.attentionStatus().equals("OPEN"))
                .count();
        long cancelled = count(run, "CANCELLED");
        long failed = count(run, "FAILED");
        boolean dryRun = run.mode() == RunMode.DRY_RUN;
        boolean settled = run.applications().stream()
                .noneMatch(item -> item.status().equals("QUEUED") || item.status().equals("IN_PROGRESS"));
        String conclusion = confirmed ? "Итог подтверждён."
                : !settled
                ? "Ожидаем завершения уже начатой операции."
                : openAttention > 0
                ? "Запуск завершён, но есть необработанные ситуации: %d.\nРазберите сообщения выше.".formatted(openAttention)
                : dryRun ? "Загрузка текстов завершена. Можно подтвердить итог запуска."
                : "Все ситуации разобраны. Можно подтвердить итог запуска.";
        String text = """
                %s %s #%d · %s
                Статус: %s

                Всего вакансий: %d
                %s: %d
                %s: %d
                Не обработано: %d

                %s
                """.formatted(run.status() == RunStatus.CANCELLED ? "🚫" : "✅",
                dryRun ? "Холостой запуск" : "Запуск", run.sequence(), run.resumeName(),
                run.status() == RunStatus.CANCELLED ? "отменён" : "завершён", run.applications().size(),
                dryRun ? "Текст загружен" : "Успешно", succeeded,
                dryRun ? "Не удалось загрузить" : "Требовали внимания", dryRun ? failed : attention,
                cancelled, conclusion).strip();
        List<TelegramBotApi.InlineButton> buttons = !confirmed && settled && openAttention == 0
                ? List.of(new TelegramBotApi.InlineButton("✅ Подтвердить", "completion:confirm:" + run.id()))
                : List.of();
        return new Presentation(text, buttons, Integer.toHexString(Objects.hash(text, buttons)));
    }

    private static long count(Run run, String status) {
        return run.applications().stream().map(Item::status).filter(status::equals).count();
    }

    record Presentation(String text, List<TelegramBotApi.InlineButton> buttons, String fingerprint) {
    }
}
