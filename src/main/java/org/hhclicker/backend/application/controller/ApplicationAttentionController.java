package org.hhclicker.backend.application.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.application.service.ApplicationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attention-tasks")
public class ApplicationAttentionController {
    private final ApplicationService applications;

    public ApplicationAttentionController(ApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    public List<Attention> list(@RequestParam UUID resumeId) {
        return applications.attention(resumeId);
    }

    @PutMapping("/{id}/status")
    public Attention status(@PathVariable UUID id, @RequestBody @Valid StatusRequest request) {
        return applications.attention(id, request.status());
    }

    public record StatusRequest(@NotNull String status) {
    }
}
