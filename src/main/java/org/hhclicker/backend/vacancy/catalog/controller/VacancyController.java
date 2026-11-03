package org.hhclicker.backend.vacancy.catalog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.vacancy.catalog.dto.VacancyResponse;
import org.hhclicker.backend.vacancy.catalog.model.VacancyText;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vacancies")
@Tag(name = "Vacancies", description = "Найденные вакансии и их текущее состояние")
public class VacancyController {

    private final VacancyCatalogService vacancyCatalogService;

    public VacancyController(VacancyCatalogService vacancyCatalogService) {
        this.vacancyCatalogService = vacancyCatalogService;
    }

    @GetMapping
    @Operation(
            summary = "Получить найденные вакансии",
            description = "searchId имеет приоритет над resumeId. Без параметров возвращает все вакансии."
    )
    public List<VacancyResponse> findAll(
            @RequestParam(required = false) UUID resumeId,
            @RequestParam(required = false) UUID searchId
    ) {
        return vacancyCatalogService.findAll(resumeId, searchId).stream()
                .map(VacancyResponse::from)
                .toList();
    }

    @GetMapping("/{vacancyId}")
    @Operation(summary = "Получить вакансию")
    @ApiResponse(responseCode = "404", description = "Вакансия не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public VacancyResponse findById(@PathVariable UUID vacancyId) {
        return VacancyResponse.from(vacancyCatalogService.findById(vacancyId));
    }

    @GetMapping("/{vacancyId}/text")
    @Operation(summary = "Получить сохранённый текст описания без открытия HH")
    public VacancyText findText(@PathVariable UUID vacancyId) {
        return vacancyCatalogService.findText(vacancyId);
    }
}
