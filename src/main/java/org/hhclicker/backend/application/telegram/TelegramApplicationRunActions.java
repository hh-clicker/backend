package org.hhclicker.backend.application.telegram;

import org.springframework.stereotype.Component;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.service.ApplicationRunGateway;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.settings.telegram.TelegramCallbackHandler;

import java.util.Optional;
import java.util.UUID;

@Component
public class TelegramApplicationRunActions implements TelegramCallbackHandler {
    private static final String PREFIX = "application:";

    private final ApplicationService applications;
    private final ApplicationRunGateway gateway;

    public TelegramApplicationRunActions(ApplicationService applications, ApplicationRunGateway gateway) {
        this.applications = applications;
        this.gateway = gateway;
    }

    @Override
    public Optional<String> handle(String data) {
        if (data == null || !data.startsWith(PREFIX)) return Optional.empty();
        String[] parts = data.split(":", 3);
        if (parts.length != 3) return Optional.of("Кнопка имеет неверный формат.");
        UUID runId;
        try {
            runId = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException exception) {
            return Optional.of("Кнопка имеет неверный формат.");
        }
        var run = applications.find(runId);
        return switch (parts[1]) {
            case "pause" -> pause(runId, run.status());
            case "resume" -> resume(runId, run.status());
            default -> Optional.of("Кнопка больше не поддерживается.");
        };
    }

    private Optional<String> pause(UUID runId, RunStatus status) {
        if (status == RunStatus.PAUSED_USER) return Optional.of("Запуск уже стоит на паузе.");
        if (status != RunStatus.RUNNING) return Optional.of("Сейчас этот запуск нельзя поставить на паузу.");
        gateway.changeApplicationStatus(runId, RunStatus.PAUSED_USER);
        return Optional.of("Запуск поставлен на паузу.");
    }

    private Optional<String> resume(UUID runId, RunStatus status) {
        if (status == RunStatus.RUNNING) return Optional.of("Запуск уже выполняется.");
        if (!status.name().startsWith("PAUSED_")) return Optional.of("Завершённый запуск нельзя продолжить.");
        gateway.changeApplicationStatus(runId, RunStatus.RUNNING);
        return Optional.of("Запуск продолжен.");
    }
}
