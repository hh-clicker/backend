package org.hhclicker.backend.agent.exception;

import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.UUID;

public class BrowserAgentCommandNotFoundException extends ApplicationException {

    public BrowserAgentCommandNotFoundException(UUID commandId) {
        super(
                ApplicationErrorCategory.NOT_FOUND,
                "BROWSER_AGENT_COMMAND_NOT_FOUND",
                "Команда Browser Agent %s не найдена.".formatted(commandId)
        );
    }
}

