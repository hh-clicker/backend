package org.hhclicker.backend.search.service;

import org.hhclicker.backend.search.model.VacancySearch;

public record SearchProfile(
        VacancySearch search,
        String resumeName
) {
}

