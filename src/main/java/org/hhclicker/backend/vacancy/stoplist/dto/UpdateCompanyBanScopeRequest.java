package org.hhclicker.backend.vacancy.stoplist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;

import java.util.UUID;

public record UpdateCompanyBanScopeRequest(
        @NotNull
        CompanyBanScope scope,

        @Schema(description = "Обязателен для области RESUME и должен отсутствовать для GLOBAL")
        UUID resumeId
) {
}
