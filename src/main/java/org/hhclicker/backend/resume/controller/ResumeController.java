package org.hhclicker.backend.resume.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.resume.dto.ImportResumesRequest;
import org.hhclicker.backend.resume.dto.ResumeProfileResponse;
import org.hhclicker.backend.resume.dto.ResumesResponse;
import org.hhclicker.backend.resume.dto.UpdateResumeSettingsRequest;
import org.hhclicker.backend.resume.service.ImportResumeItem;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.resume.service.UpdateResumeSettingsCommand;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resumes")
@Tag(name = "HH resumes", description = "Резюме HH и настройки автоматических откликов")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @GetMapping
    @Operation(summary = "Получить резюме", description = "Без accountId возвращает резюме всех HH-аккаунтов")
    public ResumesResponse findAll(@RequestParam(required = false) UUID accountId) {
        return new ResumesResponse(resumeService.findAll(accountId).stream()
                .map(ResumeProfileResponse::from)
                .toList());
    }

    @GetMapping("/{resumeId}")
    @Operation(summary = "Получить резюме")
    @ApiResponse(responseCode = "404", description = "Резюме не найдено", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResumeProfileResponse findById(@PathVariable UUID resumeId) {
        return ResumeProfileResponse.from(resumeService.findById(resumeId));
    }

    @PostMapping("/import")
    @Operation(summary = "Добавить выбранные карточки HH, сохранив существующие резюме и настройки")
    public ResumesResponse importResumes(@Valid @RequestBody ImportResumesRequest request) {
        return new ResumesResponse(resumeService.importResumes(request.accountId(), request.items().stream()
                        .map(item -> new ImportResumeItem(item.hhResumeId(), item.name(), item.plainText())).toList()).stream()
                .map(ResumeProfileResponse::from).toList());
    }

    @PutMapping("/{resumeId}/settings")
    @Operation(summary = "Изменить настройки автоматических откликов")
    public ResumeProfileResponse updateSettings(
            @PathVariable UUID resumeId,
            @Valid @RequestBody UpdateResumeSettingsRequest request
    ) {
        return ResumeProfileResponse.from(resumeService.updateSettings(resumeId, new UpdateResumeSettingsCommand(
                request.coverLetter(),
                request.dailyLimit(),
                request.pauseFromSeconds(),
                request.pauseToSeconds(),
                request.skipQuestionnaires(), request.aiLetter()
        )));
    }

    @DeleteMapping("/{resumeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить резюме")
    public void delete(@PathVariable UUID resumeId) {
        resumeService.delete(resumeId);
    }
}
