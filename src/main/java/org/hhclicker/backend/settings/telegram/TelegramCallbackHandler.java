package org.hhclicker.backend.settings.telegram;

import java.util.Optional;

public interface TelegramCallbackHandler {
    Optional<String> handle(String data);
}
