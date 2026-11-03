package org.hhclicker.backend.application.telegram;

import org.hhclicker.backend.application.model.ApplicationRunEstimate;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.telegram.TelegramBotApi;

import java.util.List;
import java.util.Objects;

final class TelegramRunPresentation {
    private TelegramRunPresentation() {
    }

    static Presentation from(NotificationRun run) {
        long attention = run.attentionIds().size();
        long processed = Math.min(run.total(), run.processed());
        long remaining = Math.max(0, run.total() - processed);
        String remainderLabel = run.status().equals("CANCELLED") ? "Не обработано" : "Осталось";
        String estimate = estimate(run, remaining);
        boolean dryRun = "DRY_RUN".equals(run.mode());
        String text = """
                %s %s #%d
                Резюме: %s

                Статус: %s
                Обработано: %d из %d
                %s: %d
                %s: %d
                %s: %d%s
                """.formatted(statusIcon(run.status()), dryRun ? "Холостой запуск" : "Запуск",
                run.sequence(), run.resumeName(), statusLabel(run.status()), processed, run.total(),
                dryRun ? "Текст загружен" : "Успешно", run.succeeded(),
                dryRun ? "Не удалось загрузить" : "Требуют внимания", dryRun ? run.failed() : attention,
                remainderLabel, remaining, estimate).strip();
        List<TelegramBotApi.InlineButton> buttons = buttons(run);
        return new Presentation(text, buttons, Integer.toHexString(Objects.hash(text, buttons)));
    }

    static boolean active(String status) {
        return status.equals("RUNNING") || status.startsWith("PAUSED_");
    }

    private static String estimate(NotificationRun run, long remaining) {
        var estimate = ApplicationRunEstimate.remainingSeconds(run.status(), run.total(), run.processed(),
                run.activeSeconds(), run.timedProcessed());
        if (estimate.isEmpty()) return "";
        long minutes = Math.max(1, Math.round(estimate.getAsLong() / 60.0));
        String duration = duration(minutes);
        return run.status().equals("RUNNING")
                ? "\nПримерно осталось: " + duration
                : "\nПосле продолжения: примерно " + duration;
    }

    private static String duration(long minutes) {
        long hours = minutes / 60;
        long remainder = minutes % 60;
        if (hours == 0) return minutes + " мин";
        if (remainder == 0) return hours + " ч";
        return hours + " ч " + remainder + " мин";
    }

    private static List<TelegramBotApi.InlineButton> buttons(NotificationRun run) {
        if (run.status().equals("RUNNING")) {
            return List.of(new TelegramBotApi.InlineButton("⏸ Пауза", "application:pause:" + run.id()));
        }
        if (run.status().startsWith("PAUSED_")) {
            return List.of(new TelegramBotApi.InlineButton("▶ Продолжить", "application:resume:" + run.id()));
        }
        return List.of();
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "RUNNING" -> "выполняется";
            case "PAUSED_USER" -> "приостановлен пользователем";
            case "PAUSED_CAPTCHA" -> "приостановлен — нужно пройти капчу";
            case "PAUSED_AUTH" -> "приостановлен — нужно восстановить вход на HH";
            case "PAUSED_ERROR" -> "приостановлен из-за ошибки";
            case "PAUSED_LIMIT" -> "приостановлен — достигнут дневной лимит";
            case "PAUSED_AI_LIMIT" -> "приостановлен — достигнут лимит Codex";
            case "COMPLETED" -> "завершён";
            case "CANCELLED" -> "отменён";
            default -> status;
        };
    }

    private static String statusIcon(String status) {
        return switch (status) {
            case "RUNNING" -> "🚀";
            case "COMPLETED" -> "✅";
            case "CANCELLED" -> "🚫";
            case "PAUSED_USER" -> "⏸️";
            case "PAUSED_CAPTCHA", "PAUSED_AUTH", "PAUSED_ERROR" -> "⚠️";
            case "PAUSED_LIMIT", "PAUSED_AI_LIMIT" -> "⏳";
            default -> "ℹ️";
        };
    }

    record Presentation(String text, List<TelegramBotApi.InlineButton> buttons, String fingerprint) {
    }
}
