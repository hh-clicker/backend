package org.hhclicker.backend.vacancy.catalog.service;

import java.util.List;
import java.util.UUID;

/**
 * Резюме определяет владелец поиска, а не HTTP-запрос.
 */
public record IngestSearchResultsCommand(
        UUID searchId,
        UUID resumeId,
        List<NormalizedVacancy> vacancies
) {
}
