package org.hhclicker.backend.vacancy.stoplist.service;

import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;

import java.util.UUID;

/**
 * Вычисляемые поля клиента (название резюме, название исходной вакансии и количество скрытых
 * вакансий) в команду не входят: backend определяет их сам.
 */
public record CreateCompanyBanCommand(
        String employerUrl,
        String companyName,
        CompanyBanScope scope,
        UUID resumeId,
        UUID sourceVacancyId
) {
}
