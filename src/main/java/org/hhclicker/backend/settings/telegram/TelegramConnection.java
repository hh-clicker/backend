package org.hhclicker.backend.settings.telegram;

import java.time.OffsetDateTime;

public record TelegramConnection(
        String botToken,
        Long botId,
        String botUsername,
        Long chatId,
        Long telegramUserId,
        String pairingCode,
        OffsetDateTime pairingExpiresAt,
        long updateOffset,
        String lastError
) {
    @Override
    public String toString() {
        return "TelegramConnection[configured=" + configured() + ", connected=" + connected()
                + ", updateOffset=" + updateOffset + "]";
    }

    public boolean configured() {
        return botToken != null;
    }

    public boolean connected() {
        return chatId != null;
    }

    public boolean pairingActive(OffsetDateTime now) {
        return pairingCode != null && pairingExpiresAt != null && pairingExpiresAt.isAfter(now);
    }
}
