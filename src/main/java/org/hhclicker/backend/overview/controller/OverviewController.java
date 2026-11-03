package org.hhclicker.backend.overview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.hhclicker.backend.overview.dto.OverviewResponse;
import org.hhclicker.backend.overview.service.OverviewService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/overview")
@Tag(name = "Overview", description = "Контекст приложения и сводные показатели")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping
    @Operation(summary = "Получить контекст приложения")
    public OverviewResponse load(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID resumeId
    ) {
        return OverviewResponse.from(overviewService.load(accountId, resumeId));
    }
}

