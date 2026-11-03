package org.hhclicker.backend.agent.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

public class BrowserAgentCommandConflictException extends ApplicationException {

    public BrowserAgentCommandConflictException(String safeMessage) {
        super(ApplicationErrorCategory.CONFLICT, "BROWSER_AGENT_COMMAND_CONFLICT", safeMessage);
    }
}

