package org.hhclicker.backend.vacancy.stoplist.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.vacancy.stoplist.dto.CompanyBanResponse;
import org.hhclicker.backend.vacancy.stoplist.dto.CreateCompanyBanRequest;
import org.hhclicker.backend.vacancy.stoplist.dto.UpdateCompanyBanScopeRequest;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanService;
import org.hhclicker.backend.vacancy.stoplist.service.CreateCompanyBanCommand;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/company-bans")
@Tag(name = "Company stop list", description = "Стоп-лист работодателей HH")
public class CompanyBanController {

    private final CompanyBanService companyBanService;

    public CompanyBanController(CompanyBanService companyBanService) {
        this.companyBanService = companyBanService;
    }

    @GetMapping
    @Operation(
            summary = "Получить стоп-лист",
            description = "С resumeId возвращает глобальные блокировки и блокировки этого резюме, без параметра — все"
    )
    public List<CompanyBanResponse> findAll(@RequestParam(required = false) UUID resumeId) {
        return companyBanService.findAll(resumeId).stream()
                .map(CompanyBanResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Добавить работодателя в стоп-лист",
            description = "Идентификатор работодателя извлекается из ссылки на backend; название, резюме и счётчики вычисляются самостоятельно"
    )
    @ApiResponse(responseCode = "201", description = "Блокировка создана")
    @ApiResponse(responseCode = "400", description = "Некорректная ссылка или область блокировки", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Резюме или исходная вакансия не найдены", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Работодатель уже заблокирован", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public CompanyBanResponse create(@Valid @RequestBody CreateCompanyBanRequest request) {
        return CompanyBanResponse.from(companyBanService.create(new CreateCompanyBanCommand(
                request.employerUrl(),
                request.companyName(),
                request.scope(),
                request.resumeId(),
                request.sourceVacancyId()
        )));
    }

    @PutMapping("/{banId}/scope")
    @Operation(
            summary = "Изменить область блокировки",
            description = "После изменения области применимость блокировок пересчитывается для всех вакансий работодателя"
    )
    @ApiResponse(responseCode = "400", description = "Некорректная область блокировки", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Блокировка не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Работодатель уже заблокирован в этой области", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public CompanyBanResponse changeScope(
            @PathVariable UUID banId,
            @Valid @RequestBody UpdateCompanyBanScopeRequest request
    ) {
        return CompanyBanResponse.from(
                companyBanService.changeScope(banId, request.scope(), request.resumeId())
        );
    }

    @DeleteMapping("/{banId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Снять блокировку",
            description = "Вакансии остаются заблокированными, если действует другая блокировка того же работодателя"
    )
    @ApiResponse(responseCode = "204", description = "Блокировка снята")
    @ApiResponse(responseCode = "404", description = "Блокировка не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public void delete(@PathVariable UUID banId) {
        companyBanService.delete(banId);
    }
}
