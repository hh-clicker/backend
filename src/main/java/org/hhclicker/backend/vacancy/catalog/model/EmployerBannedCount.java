package org.hhclicker.backend.vacancy.catalog.model;

import java.util.UUID;

/**
 * Количество скрытых блокировкой вакансий работодателя в разрезе резюме.
 */
public record EmployerBannedCount(
        String employerId,
        UUID resumeId,
        long count
) {
}
