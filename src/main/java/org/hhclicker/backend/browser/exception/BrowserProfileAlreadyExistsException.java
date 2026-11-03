package org.hhclicker.backend.browser.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class BrowserProfileAlreadyExistsException extends ApplicationException {

    public BrowserProfileAlreadyExistsException() {
        super(ApplicationErrorCategory.CONFLICT, "BROWSER_PROFILE_ALREADY_EXISTS", "Для HH-аккаунта уже создан профиль браузера.");
    }
}

