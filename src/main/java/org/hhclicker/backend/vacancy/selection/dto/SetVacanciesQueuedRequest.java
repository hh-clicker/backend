package org.hhclicker.backend.vacancy.selection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record SetVacanciesQueuedRequest(
        @Schema(description = "За один запрос допускается не больше 500 вакансий")
        @NotEmpty
        @Size(max = 500)
        List<UUID> vacancyIds,

        @NotNull
        Boolean queuedForApplication
) {
}
