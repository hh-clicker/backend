package org.hhclicker.backend.market;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/market-analyses")
public class MarketAnalysisController {
    private final MarketAnalysisService analyses;

    public MarketAnalysisController(MarketAnalysisService analyses) { this.analyses = analyses; }

    @GetMapping
    public List<MarketAnalysisService.Summary> list(@RequestParam UUID resumeId) { return analyses.list(resumeId); }

    @GetMapping("/preview")
    public MarketAnalysisService.Preview preview(@RequestParam UUID resumeId, @RequestParam(required = false) @Positive Integer maxVacancies,
                                                 @RequestParam(required = false) UUID searchId,
                                                 @RequestParam(required = false) MarketRelevanceCategory relevanceCategory) {
        return analyses.preview(resumeId, maxVacancies, searchId, relevanceCategory);
    }

    @PostMapping
    public MarketAnalysisService.Summary start(@RequestBody @Valid StartRequest request) {
        return analyses.start(request.resumeId(), request.maxVacancies(), request.searchId(), request.relevanceCategory());
    }

    @PostMapping("/{id}/stop")
    public MarketAnalysisService.Summary stop(@PathVariable UUID id) { return analyses.stop(id); }

    @PostMapping("/{id}/cancel")
    public MarketAnalysisService.Summary cancel(@PathVariable UUID id) { return analyses.cancel(id); }

    @PostMapping("/{id}/resume")
    public MarketAnalysisService.Summary resume(@PathVariable UUID id) { return analyses.resume(id); }

    @GetMapping("/{id}")
    public MarketAnalysisService.Summary find(@PathVariable UUID id) { return analyses.find(id); }

    @GetMapping(value = "/{id}/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean debug) {
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=hh-clicker-market-analysis-" + id + (debug ? "-debug" : "") + ".md")
                .body(analyses.markdown(id, debug));
    }

    public record StartRequest(@NotNull UUID resumeId, @Positive Integer maxVacancies, UUID searchId,
                               MarketRelevanceCategory relevanceCategory) { }
}
