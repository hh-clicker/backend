package org.hhclicker.backend.vacancy.selection.dto;

import jakarta.validation.constraints.NotNull;

public record SetVacancyQueuedRequest(
        @NotNull
        Boolean queuedForApplication
) {
}
