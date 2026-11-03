package org.hhclicker.backend.settings.telegram;

import org.springframework.stereotype.Service;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.util.List;
import java.util.Optional;

@Service
public class TelegramMessageService {
    private final TelegramConnectionRepository repository;
    private final TelegramBotApi api;

    public TelegramMessageService(TelegramConnectionRepository repository, TelegramBotApi api) {
        this.repository = repository;
        this.api = api;
    }

    public Optional<Target> connectedTarget() {
        TelegramConnection connection = repository.get();
        return connection.connected() ? Optional.of(new Target(connection.botId(), connection.chatId())) : Optional.empty();
    }

    public long send(Target target, String text, List<TelegramBotApi.InlineButton> buttons) {
        TelegramConnection connection = connected(target);
        return api.sendMessage(connection.botToken(), target.chatId(), text, buttons);
    }

    public void edit(Target target, long messageId, String text, List<TelegramBotApi.InlineButton> buttons) {
        TelegramConnection connection = connected(target);
        api.editMessage(connection.botToken(), target.chatId(), messageId, text, buttons);
    }

    public void delete(Target target, long messageId) {
        TelegramConnection connection = connected(target);
        api.deleteMessage(connection.botToken(), target.chatId(), messageId);
    }

    private TelegramConnection connected(Target target) {
        TelegramConnection connection = repository.get();
        if (!connection.connected() || connection.chatId() != target.chatId() || connection.botId() != target.botId()) {
            throw new TelegramSettingsException(ApplicationErrorCategory.CONFLICT, "TELEGRAM_CHAT_CHANGED",
                    "Подключение Telegram изменилось. Обновите статус интеграции.");
        }
        return connection;
    }

    public record Target(long botId, long chatId) {
    }
}
