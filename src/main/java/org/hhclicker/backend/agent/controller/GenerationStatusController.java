package org.hhclicker.backend.agent.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.hhclicker.backend.agent.service.GenerationStatusService;

@RestController
@RequestMapping("/api/v1/generation-agent/status")
public class GenerationStatusController {
    private final GenerationStatusService service;

    public GenerationStatusController(GenerationStatusService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<GenerationStatusService.View> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view());
    }

    @PostMapping("/refresh")
    public ResponseEntity<GenerationStatusService.View> refresh() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.refresh());
    }
}
