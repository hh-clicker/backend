package org.hhclicker.backend.shared.exception;

import java.util.Objects;

public class ApplicationException extends RuntimeException {

    private final ApplicationErrorCategory category;
    private final String machineCode;
    private final String safeMessage;

    public ApplicationException(
            ApplicationErrorCategory category,
            String machineCode,
            String safeMessage
    ) {
        super(safeMessage);
        this.category = Objects.requireNonNull(category);
        this.machineCode = Objects.requireNonNull(machineCode);
        this.safeMessage = Objects.requireNonNull(safeMessage);
    }

    public ApplicationException(
            ApplicationErrorCategory category,
            String machineCode,
            String safeMessage,
            Throwable cause
    ) {
        super(safeMessage, cause);
        this.category = Objects.requireNonNull(category);
        this.machineCode = Objects.requireNonNull(machineCode);
        this.safeMessage = Objects.requireNonNull(safeMessage);
    }

    public ApplicationErrorCategory category() {
        return category;
    }

    public String machineCode() {
        return machineCode;
    }

    public String safeMessage() {
        return safeMessage;
    }
}

