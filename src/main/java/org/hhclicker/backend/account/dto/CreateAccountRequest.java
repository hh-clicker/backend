package org.hhclicker.backend.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @Schema(example = "Основной HH")
        @NotBlank
        @Size(max = 120)
        String name,

        @Schema(description = "Идентификатор пользователя HH, если он уже известен", nullable = true)
        @Size(max = 100)
        String hhUserId
) {
}

