package org.hhclicker.backend.search.dto;

import org.hhclicker.backend.vacancy.catalog.service.VacancyIngestionResult;

import java.util.UUID;

public record IngestVacanciesResponse(
        UUID searchId,
        int received,
        int created,
        int updated,
        long newVacancies,
        long totalVacancies
) {

    public static IngestVacanciesResponse from(VacancyIngestionResult result) {
        return new IngestVacanciesResponse(
                result.searchId(),
                result.received(),
                result.created(),
                result.updated(),
                result.newVacancies(),
                result.totalVacancies()
        );
    }
}
