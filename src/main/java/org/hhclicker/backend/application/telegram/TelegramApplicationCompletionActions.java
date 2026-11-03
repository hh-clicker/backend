package org.hhclicker.backend.application.telegram;

import org.springframework.stereotype.Component;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.settings.telegram.TelegramCallbackHandler;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.Optional;
import java.util.UUID;

@Component
public class TelegramApplicationCompletionActions implements TelegramCallbackHandler {
    private static final String PREFIX = "completion:confirm:";

    private final ApplicationService applications;
    private final TelegramMessageService telegram;
    private final TelegramRunCompletionRepository completions;

    public TelegramApplicationCompletionActions(ApplicationService applications, TelegramMessageService telegram,
                                                TelegramRunCompletionRepository completions) {
        this.applications = applications;
        this.telegram = telegram;
        this.completions = completions;
    }

    @Override
    public Optional<String> handle(String data) {
        if (data == null || !data.startsWith(PREFIX)) return Optional.empty();
        UUID runId;
        try {
            runId = UUID.fromString(data.substring(PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            return Optional.of("Кнопка имеет неверный формат.");
        }
        TelegramMessageService.Target target = telegram.connectedTarget().orElse(null);
        var completion = target == null ? Optional.<TelegramRunCompletionRepository.Message>empty()
                : completions.find(runId, target);
        if (completion.isEmpty()) {
            return Optional.of("Итоговое сообщение больше неактуально.");
        }
        var run = applications.find(runId);
        if (run.status() != RunStatus.COMPLETED && run.status() != RunStatus.CANCELLED) {
            return Optional.of("Запуск ещё не завершён.");
        }
        boolean unsettled = run.applications().stream()
                .anyMatch(item -> item.status().equals("QUEUED") || item.status().equals("IN_PROGRESS"));
        if (unsettled) return Optional.of("Дождитесь завершения уже начатой операции.");
        boolean hasOpenAttention = run.applications().stream()
                .anyMatch(item -> item.status().equals("ATTENTION_REQUIRED") && item.attentionStatus().equals("OPEN"));
        if (hasOpenAttention) return Optional.of("Сначала разберите все ситуации, требующие внимания.");
        if (completion.orElseThrow().confirmed()) {
            return Optional.of("Итог запуска уже подтверждён.");
        }
        completions.confirm(runId, target);
        return Optional.of("Итог запуска подтверждён.");
    }
}
