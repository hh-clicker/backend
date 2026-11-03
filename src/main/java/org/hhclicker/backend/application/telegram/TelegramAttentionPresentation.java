package org.hhclicker.backend.application.telegram;

import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.settings.telegram.TelegramBotApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TelegramAttentionPresentation {
    private TelegramAttentionPresentation() {
    }

    static Presentation from(Attention attention) {
        boolean open = attention.status().equals("OPEN");
        String text = """
                %s %s
                Запуск #%d · %s

                Вакансия: %s
                Компания: %s

                %s
                Статус: %s
                """.formatted(icon(attention), attention.title(), attention.runSequence(), attention.resumeName(),
                attention.vacancyTitle(), attention.companyName(), attention.description(), status(attention.status())).strip();
        List<TelegramBotApi.InlineButton> buttons = new ArrayList<>();
        if (open) {
            buttons.add(new TelegramBotApi.InlineButton("✅ Готово", "attention:resolve:" + attention.id()));
            if (attention.canSkipVacancy()) {
                buttons.add(new TelegramBotApi.InlineButton("🚫 В пропущенные", "attention:skip:" + attention.id()));
            }
        }
        return new Presentation(text, List.copyOf(buttons), Integer.toHexString(Objects.hash(text, buttons)), !open);
    }

    private static String icon(Attention attention) {
        if (attention.status().equals("RESOLVED")) return "✅";
        if (attention.status().equals("SKIPPED")) return "🚫";
        return switch (attention.kind()) {
            case "QUESTIONNAIRE" -> "📝";
            case "CAPTCHA" -> "🧩";
            case "AUTH" -> "🔐";
            default -> "⚠️";
        };
    }

    private static String status(String status) {
        return switch (status) {
            case "OPEN" -> "ожидает решения";
            case "RESOLVED" -> "отмечено как решённое";
            case "SKIPPED" -> "вакансия добавлена в пропущенные";
            default -> status;
        };
    }

    record Presentation(String text, List<TelegramBotApi.InlineButton> buttons, String fingerprint, boolean closed) {
    }
}
