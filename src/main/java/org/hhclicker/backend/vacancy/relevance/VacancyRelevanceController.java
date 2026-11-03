package org.hhclicker.backend.vacancy.relevance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vacancies/relevance")
public class VacancyRelevanceController {

    private final VacancyRelevanceService service;

    public VacancyRelevanceController(VacancyRelevanceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VacancyRelevanceRunResponse start(@Valid @RequestBody VacancyRelevanceRequest request) {
        return service.start(request);
    }

    @GetMapping("/{id}")
    public VacancyRelevanceRunResponse find(@PathVariable UUID id) {
        return service.find(id);
    }

    @GetMapping("/active")
    public ResponseEntity<VacancyRelevanceRunResponse> active(@RequestParam UUID resumeId) {
        VacancyRelevanceRunResponse active = service.active(resumeId);
        return active == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(active);
    }
}
