package org.hhclicker.backend.vacancy.favorites.model;

import java.util.List;
import java.util.UUID;

public record VacancyFavorites(UUID accountId, List<String> vacancyIds, List<String> employerIds) {
}
