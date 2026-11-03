package org.hhclicker.backend.application.telegram;

import org.springframework.stereotype.Component;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.settings.telegram.TelegramCallbackHandler;

import java.util.Optional;
import java.util.UUID;

@Component
public class TelegramApplicationAttentionActions implements TelegramCallbackHandler {
    private static final String PREFIX = "attention:";

    private final ApplicationService applications;

    public TelegramApplicationAttentionActions(ApplicationService applications) {
        this.applications = applications;
    }

    @Override
    public Optional<String> handle(String data) {
        if (data == null || !data.startsWith(PREFIX)) return Optional.empty();
        String[] parts = data.split(":", 3);
        if (parts.length != 3) return Optional.of("Кнопка имеет неверный формат.");
        UUID attentionId;
        try {
            attentionId = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException exception) {
            return Optional.of("Кнопка имеет неверный формат.");
        }
        var attention = applications.attentionTask(attentionId);
        if (!attention.status().equals("OPEN")) return Optional.of("Эта ситуация уже обработана.");
        return switch (parts[1]) {
            case "resolve" -> resolve(attentionId);
            case "skip" -> attention.canSkipVacancy() ? skip(attentionId)
                    : Optional.of("Эту вакансию больше нельзя добавить в пропущенные.");
            default -> Optional.of("Кнопка больше не поддерживается.");
        };
    }

    private Optional<String> resolve(UUID attentionId) {
        applications.attention(attentionId, "RESOLVED");
        return Optional.of("Ситуация отмечена как решённая.");
    }

    private Optional<String> skip(UUID attentionId) {
        applications.attention(attentionId, "SKIPPED");
        return Optional.of("Вакансия добавлена в пропущенные.");
    }
}
