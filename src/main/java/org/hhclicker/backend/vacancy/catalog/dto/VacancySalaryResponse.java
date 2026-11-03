package org.hhclicker.backend.vacancy.catalog.dto;

import org.hhclicker.backend.vacancy.catalog.model.SalaryCurrency;
import org.hhclicker.backend.vacancy.catalog.model.VacancySalary;

public record VacancySalaryResponse(
        Long from,
        Long to,
        SalaryCurrency currency,
        boolean gross
) {

    public static VacancySalaryResponse from(VacancySalary salary) {
        if (salary == null) {
            return null;
        }
        return new VacancySalaryResponse(salary.from(), salary.to(), salary.currency(), salary.gross());
    }
}
