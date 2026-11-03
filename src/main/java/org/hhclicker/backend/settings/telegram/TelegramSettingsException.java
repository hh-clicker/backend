package org.hhclicker.backend.settings.telegram;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

final class TelegramSettingsException extends ApplicationException {
    TelegramSettingsException(ApplicationErrorCategory category, String code, String message) {
        super(category, code, message);
    }
}
