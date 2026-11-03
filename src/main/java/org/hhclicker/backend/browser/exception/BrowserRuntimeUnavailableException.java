package org.hhclicker.backend.browser.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class BrowserRuntimeUnavailableException extends ApplicationException {

    public BrowserRuntimeUnavailableException() {
        super(ApplicationErrorCategory.EXTERNAL_SYSTEM, "BROWSER_AGENT_UNAVAILABLE", "Browser Agent не подключён или ещё не готов.");
    }
}
