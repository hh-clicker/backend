package org.hhclicker.backend.resume.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record ImportResumesRequest(
        @NotNull UUID accountId,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Item> items
) {
    public record Item(
            @NotBlank @Pattern(regexp = "[a-f0-9]{20,100}") String hhResumeId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 100000) String plainText
    ) {
    }
}
