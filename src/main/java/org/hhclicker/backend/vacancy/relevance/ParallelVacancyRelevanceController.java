package org.hhclicker.backend.vacancy.relevance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vacancies/relevance/parallel")
public class ParallelVacancyRelevanceController {
    private final ParallelVacancyRelevanceService service;

    public ParallelVacancyRelevanceController(ParallelVacancyRelevanceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ParallelVacancyRelevanceResponse start(@Valid @RequestBody ParallelVacancyRelevanceRequest request) {
        return service.start(request);
    }

    @GetMapping("/active")
    public ResponseEntity<ParallelVacancyRelevanceResponse> active(@RequestParam UUID resumeId) {
        var run = service.active(resumeId);
        return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
    }

    @GetMapping("/{id}")
    public ParallelVacancyRelevanceResponse find(@PathVariable UUID id) {
        return service.find(id);
    }

    @DeleteMapping("/{id}")
    public ParallelVacancyRelevanceResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
