package org.hhclicker.backend.settings.telegram;

public class TelegramMessageNotFoundException extends RuntimeException {
    public TelegramMessageNotFoundException(Throwable cause) {
        super("Telegram message no longer exists", cause);
    }
}
