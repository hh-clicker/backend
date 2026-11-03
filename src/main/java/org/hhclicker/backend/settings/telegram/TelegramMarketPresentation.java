package org.hhclicker.backend.settings.telegram;

import org.hhclicker.backend.market.MarketAnalysisService;
import java.util.Objects;

final class TelegramMarketPresentation {
    private TelegramMarketPresentation() { }

    static String text(MarketAnalysisService.NotificationRun run) {
        String name = run.resumeName() == null ? "—" : run.resumeName().strip();
        String status = switch (run.status()) {
            case "RUNNING" -> "🚀 Анализ рынка выполняется";
            case "STOPPING" -> "⏳ Анализ рынка останавливается";
            case "PAUSED" -> "⏸ Анализ рынка приостановлен";
            case "COMPLETED" -> "✅ Анализ рынка завершён";
            case "FAILED" -> "⚠️ Анализ рынка завершился с ошибкой";
            case "CANCELLED" -> "🚫 Анализ рынка отменён";
            default -> "ℹ️ Анализ рынка: " + run.status();
        };
        int total = Math.max(0, run.total());
        int processed = Math.max(0, Math.min(run.processed(), total));
        String phase = "RUNNING".equals(run.status()) && processed == total && "EXTRACT".equals(run.phase())
                ? "\nСобираем отчёт."
                : "";
        String error = "FAILED".equals(run.status()) && run.error() != null && !run.error().isBlank()
                ? "\nПричина: " + run.error().strip().substring(0, Math.min(500, run.error().strip().length()))
                : "";
        return "%s\nРезюме: %s\nОбработано: %d из %d%s%s".formatted(status, name, processed, total, phase, error);
    }

    static String fingerprint(String text) {
        return Integer.toHexString(Objects.hash(text));
    }

    static boolean active(String status) {
        return "RUNNING".equals(status) || "STOPPING".equals(status);
    }
}
