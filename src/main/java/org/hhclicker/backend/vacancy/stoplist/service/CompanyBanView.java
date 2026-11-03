package org.hhclicker.backend.vacancy.stoplist.service;

import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;

/**
 * Блокировка вместе с вычисленными backend полями: клиенту они не доверяются.
 */
public record CompanyBanView(
        CompanyBan ban,
        String resumeName,
        String sourceVacancyTitle,
        long hiddenVacanciesCount
) {
}
