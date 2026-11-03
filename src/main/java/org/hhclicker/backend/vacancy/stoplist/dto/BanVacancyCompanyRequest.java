package org.hhclicker.backend.vacancy.stoplist.dto;

import jakarta.validation.constraints.NotNull;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;

public record BanVacancyCompanyRequest(
        @NotNull
        CompanyBanScope scope
) {
}
