package org.hhclicker.backend.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.agent.dto.BrowserAgentCommandResponse;
import org.hhclicker.backend.agent.dto.BrowserAgentScanProgressRequest;
import org.hhclicker.backend.agent.dto.CompleteBrowserAgentCommandRequest;
import org.hhclicker.backend.agent.exception.InvalidBrowserAgentDataException;
import org.hhclicker.backend.agent.service.BrowserAgentCommandService;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/browser-agent/commands")
@Tag(name = "Browser Agent commands", description = "Эфемерные команды локальному Browser Agent")
public class BrowserAgentCommandController {

    private final BrowserAgentCommandService commandService;

    public BrowserAgentCommandController(BrowserAgentCommandService commandService) {
        this.commandService = commandService;
    }

    @GetMapping("/next")
    @Operation(summary = "Забрать следующую команду")
    @ApiResponse(responseCode = "204", description = "Ожидающих команд нет")
    public ResponseEntity<BrowserAgentCommandResponse> claimNext(
            @RequestParam @NotBlank @Size(max = 100) String agentId
    ) {
        return commandService.claimNext(requireAgentId(agentId))
                .map(BrowserAgentCommandResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{commandId}/result")
    @Operation(summary = "Принять результат выполнения команды")
    public BrowserAgentCommandResponse complete(
            @PathVariable UUID commandId,
            @Valid @RequestBody CompleteBrowserAgentCommandRequest request
    ) {
        return BrowserAgentCommandResponse.from(commandService.complete(
                commandId,
                request.agentId(),
                request.status(),
                request.errorMessage(),
                request.authorization() == null ? null : request.authorization().toModel(),
                request.resumes() == null ? null : request.resumes().stream().map(item -> item.toModel()).toList(),
                request.vacancies() == null ? null : request.vacancies().stream().map(item -> item.toModel()).toList(),
                request.application(), request.applicationBlocker(), request.vacancyPlainText()
        ));
    }

    @GetMapping("/{commandId}")
    @Operation(summary = "Получить состояние команды")
    public BrowserAgentCommandResponse findById(@PathVariable UUID commandId) {
        return BrowserAgentCommandResponse.from(commandService.findById(commandId));
    }

    @PostMapping("/{commandId}/scan-progress")
    @Operation(summary = "Принять промежуточный прогресс сканирования")
    public void updateScanProgress(
            @PathVariable UUID commandId,
            @Valid @RequestBody BrowserAgentScanProgressRequest request
    ) {
        commandService.updateScanProgress(commandId, request.agentId(), request.currentPage(),
                request.totalPages(), request.collected(), request.totalResults());
    }

    @PostMapping("/{commandId}/application-letter")
    public ApplicationControl prepareLetter(@PathVariable UUID commandId, @RequestParam @NotBlank String agentId,
                                            @Valid @RequestBody PrepareLetterRequest request) {
        return new ApplicationControl(commandService.prepareApplicationLetter(commandId, requireAgentId(agentId),
                new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter(
                        request.text(), request.source(), request.errorCode(), request.generationId(), request.resetsAt())));
    }

    @GetMapping("/{commandId}/application-control")
    public ApplicationControl applicationControl(@PathVariable UUID commandId, @RequestParam @NotBlank String agentId) {
        return new ApplicationControl(commandService.applicationControl(commandId, requireAgentId(agentId), false));
    }

    @PostMapping("/{commandId}/application-submit")
    public ApplicationControl applicationSubmit(@PathVariable UUID commandId, @RequestParam @NotBlank String agentId) {
        return new ApplicationControl(commandService.applicationControl(commandId, requireAgentId(agentId), true));
    }

    private String requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new InvalidBrowserAgentDataException("Не указан идентификатор Browser Agent.");
        }
        return agentId.trim();
    }

    public record ApplicationControl(boolean allowed) {
    }

    public record PrepareLetterRequest(@jakarta.validation.constraints.NotNull @Size(max = 10000) String text,
                                       @NotBlank @Size(max = 16) String source, @Size(max = 64) String errorCode,
                                       UUID generationId, @jakarta.validation.constraints.PositiveOrZero Long resetsAt) {
        public PrepareLetterRequest(String text, String source, String errorCode, UUID generationId) { this(text, source, errorCode, generationId, null); }
    }
}
