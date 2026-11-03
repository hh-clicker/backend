package org.hhclicker.backend.vacancy.selection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.vacancy.catalog.dto.VacancyResponse;
import org.hhclicker.backend.vacancy.selection.dto.SetVacanciesQueuedRequest;
import org.hhclicker.backend.vacancy.selection.dto.SetVacancyQueuedRequest;
import org.hhclicker.backend.vacancy.selection.dto.VacancyVerificationReceiptResponse;
import org.hhclicker.backend.vacancy.selection.service.QuestionnaireApplicationVerificationGateway;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vacancies")
@Tag(name = "Vacancy selection", description = "Очередь откликов и ручные состояния вакансий")
public class VacancySelectionController {

    private final VacancySelectionService vacancySelectionService;
    private final QuestionnaireApplicationVerificationGateway verificationGateway;

    public VacancySelectionController(VacancySelectionService vacancySelectionService,
                                      QuestionnaireApplicationVerificationGateway verificationGateway) {
        this.vacancySelectionService = vacancySelectionService;
        this.verificationGateway = verificationGateway;
    }

    @PutMapping("/{vacancyId}/application-queue")
    @Operation(
            summary = "Изменить участие вакансии в очереди откликов",
            description = "Статус вакансии не меняется. Поставить в очередь можно только NEW или USER_SKIPPED."
    )
    @ApiResponse(responseCode = "404", description = "Вакансия не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Статус вакансии не допускает постановку в очередь", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public VacancyResponse setQueued(
            @PathVariable UUID vacancyId,
            @Valid @RequestBody SetVacancyQueuedRequest request
    ) {
        return VacancyResponse.from(
                vacancySelectionService.setQueued(vacancyId, request.queuedForApplication())
        );
    }

    @PutMapping("/application-queue")
    @Operation(
            summary = "Изменить очередь откликов для нескольких вакансий",
            description = "Транзакционная операция: недопустимый переход хотя бы одной вакансии отменяет весь запрос."
    )
    @ApiResponse(responseCode = "400", description = "Некорректный список вакансий", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Одна из вакансий не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Статус вакансии не допускает постановку в очередь", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public List<VacancyResponse> setManyQueued(@Valid @RequestBody SetVacanciesQueuedRequest request) {
        return vacancySelectionService.setQueued(request.vacancyIds(), request.queuedForApplication()).stream()
                .map(VacancyResponse::from)
                .toList();
    }

    @PutMapping("/{vacancyId}/skip")
    @Operation(summary = "Пропустить вакансию", description = "Переводит NEW или USER_SKIPPED в USER_SKIPPED и снимает с очереди")
    @ApiResponse(responseCode = "404", description = "Вакансия не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Недопустимый переход", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public VacancyResponse skip(@PathVariable UUID vacancyId) {
        return VacancyResponse.from(vacancySelectionService.skip(vacancyId));
    }

    @PutMapping("/{vacancyId}/restore")
    @Operation(summary = "Вернуть пропущенную вакансию в работу", description = "Переводит USER_SKIPPED в NEW без автоматической постановки в очередь")
    @ApiResponse(responseCode = "404", description = "Вакансия не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Недопустимый переход", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public VacancyResponse restore(@PathVariable UUID vacancyId) {
        return VacancyResponse.from(vacancySelectionService.restore(vacancyId));
    }

    @PostMapping("/{vacancyId}/questionnaire-application-check")
    @Operation(summary = "Проверить ручной отклик с анкетой",
            description = "Запускает безопасную проверку деталки HH; подтверждённый отклик переводит вакансию в ALREADY_APPLIED")
    @ApiResponse(responseCode = "404", description = "Вакансия не найдена", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Недопустимый переход", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public VacancyVerificationReceiptResponse verifyQuestionnaireApplication(@PathVariable UUID vacancyId) {
        return new VacancyVerificationReceiptResponse(verificationGateway.verifyQuestionnaireApplication(vacancyId));
    }
}
