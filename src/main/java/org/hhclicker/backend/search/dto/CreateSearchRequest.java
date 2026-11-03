package org.hhclicker.backend.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.UUID;

public record CreateSearchRequest(
        @NotBlank
        @Size(min = 3, max = 120)
        String name,

        @Schema(example = "https://hh.ru/search/vacancy?resume=11111111111111111111111111111111111111&area=1")
        @NotBlank
        @Size(max = 4096)
        String sourceUrl,

        @NotNull
        UUID resumeId,

        @Schema(description = "Отображаемое имя с клиента игнорируется; backend получает его из резюме")
        String resumeName,

        @Min(1)
        @Max(20)
        int pagesLimit
) {
}

