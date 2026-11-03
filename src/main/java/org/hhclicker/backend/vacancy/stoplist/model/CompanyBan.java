package org.hhclicker.backend.vacancy.stoplist.model;

import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;

import java.time.Instant;
import java.util.UUID;

public record CompanyBan(
        UUID id,
        String employerId,
        String employerUrl,
        String companyName,
        CompanyBanScope scope,
        UUID resumeId,
        UUID sourceVacancyId,
        Instant createdAt,
        Instant updatedAt
) {
}
