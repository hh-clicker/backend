package org.hhclicker.backend.agent.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class InvalidBrowserAgentDataException extends ApplicationException {

    public InvalidBrowserAgentDataException(String safeMessage) {
        super(ApplicationErrorCategory.VALIDATION, "INVALID_BROWSER_AGENT_DATA", safeMessage);
    }
}

