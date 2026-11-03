package org.hhclicker.backend.vacancy.stoplist.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.vacancy.catalog.dto.VacancyResponse;
import org.hhclicker.backend.vacancy.stoplist.dto.BanVacancyCompanyRequest;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vacancies")
@Tag(name = "Company stop list", description = "Стоп-лист работодателей HH")
public class VacancyCompanyBanController {

    private final CompanyBanService companyBanService;

    public VacancyCompanyBanController(CompanyBanService companyBanService) {
        this.companyBanService = companyBanService;
    }

    @PostMapping("/{vacancyId}/company-ban")
    @Operation(
            summary = "Заблокировать работодателя вакансии",
            description = "RESUME затрагивает вакансии резюме этой вакансии, GLOBAL — вакансии всех резюме. Возвращает вакансии, состояние которых изменилось."
    )
    @ApiResponse(responseCode = "400", description = "Некорректная область блокировки", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Вакансия не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Работодатель уже заблокирован", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public List<VacancyResponse> banCompany(
            @PathVariable UUID vacancyId,
            @Valid @RequestBody BanVacancyCompanyRequest request
    ) {
        return companyBanService.banVacancyCompany(vacancyId, request.scope()).stream()
                .map(VacancyResponse::from)
                .toList();
    }
}
