package org.hhclicker.backend.vacancy.catalog.dto;

import tools.jackson.databind.JsonNode;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VacancyResponse(
        UUID id,
        UUID resumeId,
        String hhVacancyId,
        UUID searchId,
        String title,
        VacancyCompanyResponse company,
        VacancySalaryResponse salary,
        String location,
        String workFormat,
        String experience,
        Instant publishedAt,
        String salaryText,
        List<String> labels,
        VacancyStatus status,
        boolean queuedForApplication,
        CompanyBanScope banScope,
        Boolean hasQuestionnaire,
        Instant firstSeenAt,
        Instant lastSeenAt,
        boolean hasUnconfirmedApplication,
        VacancyRelevanceResponse relevance,
        JsonNode enrichment,
        int textLength
) {

    public static VacancyResponse from(VacancyListing listing) {
        Vacancy vacancy = listing.vacancy();
        return new VacancyResponse(
                vacancy.id(),
                vacancy.resumeId(),
                vacancy.hhVacancyId(),
                listing.searchId(),
                vacancy.title(),
                new VacancyCompanyResponse(vacancy.employerId(), vacancy.companyName()),
                VacancySalaryResponse.from(vacancy.salary()),
                vacancy.location(),
                vacancy.workFormat(),
                vacancy.experience(),
                vacancy.publishedAt(),
                vacancy.salaryText(),
                vacancy.labels(),
                vacancy.status(),
                vacancy.queuedForApplication(),
                vacancy.banScope(),
                vacancy.hasQuestionnaire(),
                vacancy.firstSeenAt(),
                vacancy.lastSeenAt(),
                listing.hasUnconfirmedApplication(),
                VacancyRelevanceResponse.from(vacancy.relevance()),
                vacancy.enrichment(),
                vacancy.textLength()
        );
    }
}
