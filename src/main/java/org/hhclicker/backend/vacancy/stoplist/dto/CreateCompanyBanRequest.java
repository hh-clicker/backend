package org.hhclicker.backend.vacancy.stoplist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;

import java.util.UUID;

public record CreateCompanyBanRequest(
        @Schema(example = "https://hh.ru/employer/3529")
        @NotBlank
        @Size(max = 500)
        String employerUrl,

        @Schema(description = "Необязательно: при пустом значении название берётся из вакансий работодателя")
        @Size(max = 300)
        String companyName,

        @NotNull
        CompanyBanScope scope,

        @Schema(description = "Обязателен для области RESUME и должен отсутствовать для GLOBAL")
        UUID resumeId,

        @Schema(description = "Отображаемое имя резюме с клиента игнорируется; backend получает его из резюме")
        String resumeName,

        UUID sourceVacancyId,

        @Schema(description = "Название исходной вакансии с клиента игнорируется; backend получает его из вакансии")
        String sourceVacancyTitle,

        @Schema(description = "Количество скрытых вакансий с клиента игнорируется; backend вычисляет его запросом")
        Long hiddenVacanciesCount
) {
}
