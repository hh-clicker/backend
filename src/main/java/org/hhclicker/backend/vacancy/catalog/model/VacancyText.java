package org.hhclicker.backend.vacancy.catalog.model;

import java.time.Instant;

/**
 * null text = not captured; empty text = recognized description with no readable text.
 */
public record VacancyText(String plainText, Instant textLoadedAt) {
}
