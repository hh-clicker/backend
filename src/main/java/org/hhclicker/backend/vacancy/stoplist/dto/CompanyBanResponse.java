package org.hhclicker.backend.vacancy.stoplist.dto;

import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanView;

import java.time.Instant;
import java.util.UUID;

public record CompanyBanResponse(
        UUID id,
        String employerId,
        String employerUrl,
        String companyName,
        CompanyBanScope scope,
        UUID resumeId,
        String resumeName,
        UUID sourceVacancyId,
        String sourceVacancyTitle,
        Instant createdAt,
        long hiddenVacanciesCount
) {

    public static CompanyBanResponse from(CompanyBanView view) {
        CompanyBan ban = view.ban();
        return new CompanyBanResponse(
                ban.id(),
                ban.employerId(),
                ban.employerUrl(),
                ban.companyName(),
                ban.scope(),
                ban.resumeId(),
                view.resumeName(),
                ban.sourceVacancyId(),
                view.sourceVacancyTitle(),
                ban.createdAt(),
                view.hiddenVacanciesCount()
        );
    }
}
