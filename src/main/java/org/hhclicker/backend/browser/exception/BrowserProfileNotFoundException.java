package org.hhclicker.backend.browser.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class BrowserProfileNotFoundException extends ApplicationException {

    public BrowserProfileNotFoundException(UUID id) {
        super(ApplicationErrorCategory.NOT_FOUND, "BROWSER_PROFILE_NOT_FOUND", "Профиль браузера %s не найден.".formatted(id));
    }
}

