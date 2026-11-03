package org.hhclicker.backend.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSearchRequest(
        @NotBlank @Size(min = 3, max = 120) String name,
        @NotBlank @Size(max = 4096) String sourceUrl,
        @Min(1) @Max(20) int pagesLimit
) {
}
