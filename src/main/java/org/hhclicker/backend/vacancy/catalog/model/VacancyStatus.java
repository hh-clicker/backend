package org.hhclicker.backend.vacancy.catalog.model;

public enum VacancyStatus {
    NEW,
    USER_SKIPPED,
    COMPANY_BANNED,
    ALREADY_APPLIED,
    CLOSED;

    public boolean isActive() {
        return this == NEW || this == USER_SKIPPED;
    }

    public boolean isTerminal() {
        return this == ALREADY_APPLIED || this == CLOSED;
    }
}
