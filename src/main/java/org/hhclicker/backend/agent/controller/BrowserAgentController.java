package org.hhclicker.backend.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.agent.dto.BrowserAgentHeartbeatRequest;
import org.hhclicker.backend.agent.dto.BrowserAgentStatusResponse;
import org.hhclicker.backend.agent.service.BrowserAgentStatusService;

@RestController
@RequestMapping("/api/v1/browser-agent")
@Tag(name = "Browser Agent", description = "Состояние локального Browser Agent")
public class BrowserAgentController {

    private final BrowserAgentStatusService statusService;
    private final org.hhclicker.backend.agent.service.GenerationStatusService generationStatus;

    public BrowserAgentController(BrowserAgentStatusService statusService, org.hhclicker.backend.agent.service.GenerationStatusService generationStatus) {
        this.statusService = statusService;
        this.generationStatus = generationStatus;
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "Принять heartbeat Browser Agent")
    public BrowserAgentStatusResponse heartbeat(
            @Valid @RequestBody BrowserAgentHeartbeatRequest request
    ) {
        var snapshot = statusService.heartbeat(
                request.agentId(),
                request.version(),
                request.status(),
                request.browserOpen(),
                request.activeProfileId()
        );
        generationStatus.accept(request.agentId(), request.generation());
        return BrowserAgentStatusResponse.from(snapshot).withGenerationCheckId(generationStatus.requestId());
    }

    @GetMapping("/status")
    @Operation(summary = "Получить состояние Browser Agent")
    public BrowserAgentStatusResponse getStatus() {
        return BrowserAgentStatusResponse.from(statusService.getStatus());
    }
}
