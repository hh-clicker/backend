package org.hhclicker.backend.browser.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidSelectorDiagnosticRequestException extends ApplicationException {
    public InvalidSelectorDiagnosticRequestException() {
        super(ApplicationErrorCategory.VALIDATION, "INVALID_SELECTOR_DIAGNOSTIC_REQUEST", "Укажите режим CURRENT или FULL для диагностики селекторов.");
    }
}
