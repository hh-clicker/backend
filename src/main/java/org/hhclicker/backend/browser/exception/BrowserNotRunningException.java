package org.hhclicker.backend.browser.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class BrowserNotRunningException extends ApplicationException {

    public BrowserNotRunningException() {
        super(ApplicationErrorCategory.CONFLICT, "BROWSER_NOT_RUNNING", "Сначала запустите браузер для выбранного HH-аккаунта.");
    }
}
