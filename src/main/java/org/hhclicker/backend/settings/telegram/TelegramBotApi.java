package org.hhclicker.backend.settings.telegram;

import java.util.List;

public interface TelegramBotApi {
    BotIdentity identity(String token);

    String webhookUrl(String token);

    List<Update> updates(String token, long offset, int timeoutSeconds);

    long sendMessage(String token, long chatId, String text, List<InlineButton> buttons);

    void editMessage(String token, long chatId, long messageId, String text, List<InlineButton> buttons);

    void deleteMessage(String token, long chatId, long messageId);

    void answerCallback(String token, String callbackId, String text);

    record BotIdentity(long id, String username) {
    }

    record Update(long id, Message message, Callback callback) {
        public Update(long id, Message message) {
            this(id, message, null);
        }
    }

    record Message(long chatId, String chatType, Long userId, String text) {
    }

    record Callback(String id, long chatId, long messageId, Long userId, String data) {
    }

    record InlineButton(String text, String data) {
    }
}
