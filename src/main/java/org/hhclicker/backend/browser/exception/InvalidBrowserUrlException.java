package org.hhclicker.backend.browser.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidBrowserUrlException extends ApplicationException {

    public InvalidBrowserUrlException() {
        super(ApplicationErrorCategory.VALIDATION, "INVALID_BROWSER_URL", "Ссылка должна вести на hh.ru.");
    }
}
