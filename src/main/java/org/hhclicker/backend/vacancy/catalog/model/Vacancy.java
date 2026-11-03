package org.hhclicker.backend.vacancy.catalog.model;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Vacancy(
        UUID id,
        UUID resumeId,
        String hhVacancyId,
        String title,
        String employerId,
        String companyName,
        VacancySalary salary,
        String location,
        String workFormat,
        String experience,
        Instant publishedAt,
        String salaryText,
        List<String> labels,
        Boolean hasQuestionnaire,
        VacancyStatus status,
        CompanyBanScope banScope,
        boolean queuedForApplication,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt,
        VacancyRelevance relevance,
        JsonNode enrichment,
        int textLength
) {

    public Vacancy(
            UUID id, UUID resumeId, String hhVacancyId, String title, String employerId, String companyName,
            VacancySalary salary, String location, String workFormat, String experience, Instant publishedAt,
            String salaryText, List<String> labels, Boolean hasQuestionnaire, VacancyStatus status,
            CompanyBanScope banScope, boolean queuedForApplication, Instant firstSeenAt, Instant lastSeenAt,
            Instant createdAt, Instant updatedAt, VacancyRelevance relevance, JsonNode enrichment
    ) {
        this(id, resumeId, hhVacancyId, title, employerId, companyName, salary, location, workFormat, experience,
                publishedAt, salaryText, labels, hasQuestionnaire, status, banScope, queuedForApplication,
                firstSeenAt, lastSeenAt, createdAt, updatedAt, relevance, enrichment, 0);
    }

    public Vacancy(
            UUID id, UUID resumeId, String hhVacancyId, String title, String employerId, String companyName,
            VacancySalary salary, String location, String workFormat, String experience, Instant publishedAt,
            String salaryText, List<String> labels, Boolean hasQuestionnaire, VacancyStatus status,
            CompanyBanScope banScope, boolean queuedForApplication, Instant firstSeenAt, Instant lastSeenAt,
            Instant createdAt, Instant updatedAt, VacancyRelevance relevance
    ) {
        this(id, resumeId, hhVacancyId, title, employerId, companyName, salary, location, workFormat, experience,
                publishedAt, salaryText, labels, hasQuestionnaire, status, banScope, queuedForApplication,
                firstSeenAt, lastSeenAt, createdAt, updatedAt, relevance, null, 0);
    }

    public Vacancy withState(
            VacancyStatus newStatus,
            CompanyBanScope newBanScope,
            boolean newQueuedForApplication,
            Instant newUpdatedAt
    ) {
        return new Vacancy(
                id, resumeId, hhVacancyId, title, employerId, companyName, salary,
                location, workFormat, experience, publishedAt, salaryText, labels, hasQuestionnaire,
                newStatus, newBanScope, newQueuedForApplication,
                firstSeenAt, lastSeenAt, createdAt, newUpdatedAt, relevance, enrichment, textLength
        );
    }
}
