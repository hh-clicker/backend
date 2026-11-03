package org.hhclicker.backend.market;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.market.MarketAnalysisBatchRepository.Batch;
import org.hhclicker.backend.market.MarketAnalysisRepository.*;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** All checkpoints lock the parent row; HTTP never runs inside these transactions. */
@Service
public class MarketAnalysisBatchService {
    private final MarketAnalysisRepository runs;
    private final MarketAnalysisBatchRepository batches;
    private final Clock clock;

    public MarketAnalysisBatchService(MarketAnalysisRepository runs, MarketAnalysisBatchRepository batches, Clock clock) {
        this.runs = runs;
        this.batches = batches;
        this.clock = clock;
    }

    public boolean owns(UUID id) { return batches.exists(id); }
    public List<Batch> list(UUID id) { return batches.list(id); }
    public Run load(UUID id) {
        return runs.find(id).orElseThrow(() -> new ApplicationException(ApplicationErrorCategory.NOT_FOUND,
                "MARKET_ANALYSIS_NOT_FOUND", "Анализ не найден."));
    }

    @Transactional
    public void initialize(Run run) {
        runs.insert(run);
        for (int start = 0, index = 0; start < run.sources().size(); start += 8, index++) {
            batches.insert(new Batch(run.id(), index, start, Math.min(8, run.sources().size() - start),
                    "READY", 0, null, null, null));
        }
    }

    @Transactional
    public Batch claim(UUID id, int index) {
        Run run = locked(id);
        Batch batch = batch(id, index);
        if (!Set.of("RUNNING", "STOPPING").contains(run.status())) return null;
        if (batch.active()) return batch;
        if (!"RUNNING".equals(run.status()) || run.error() != null || !"READY".equals(batch.status())) return null;
        Batch claimed = change(batch, "SUBMITTING", batch.attempt(), batch.jobId(), batch.errorCode(), batch.errorMessage());
        batches.save(claimed);
        return claimed;
    }

    @Transactional
    public Batch submitted(Batch expected, UUID job) {
        Run run = locked(expected.runId());
        Batch current = batch(expected.runId(), expected.index());
        if (!accepts(run, current, expected)) return null;
        Batch submitted = change(current, "SUBMITTED", current.attempt(), job, current.errorCode(), current.errorMessage());
        batches.save(submitted);
        return submitted;
    }

    @Transactional
    public Batch retry(Batch expected) {
        Run run = locked(expected.runId());
        Batch current = batch(expected.runId(), expected.index());
        if (!"RUNNING".equals(run.status()) || run.error() != null || !accepts(run, current, expected)) return null;
        Batch retry = change(current, "SUBMITTING", current.attempt() + 1, null, null, null);
        batches.save(retry);
        return retry;
    }

    @Transactional
    public void complete(Batch expected, MarketExtractionResults.Accepted accepted) {
        Run run = locked(expected.runId());
        Batch current = batch(expected.runId(), expected.index());
        if (!accepts(run, current, expected) || !Objects.equals(current.jobId(), expected.jobId())) return;
        List<Fact> facts = new ArrayList<>(run.facts());
        facts.addAll(accepted.facts());
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < run.sources().size(); i++) order.put(run.sources().get(i).id(), i);
        facts.sort(Comparator.comparingInt(fact -> order.getOrDefault(fact.vacancyId(), Integer.MAX_VALUE)));
        List<Diagnostic> diagnostics = new ArrayList<>(run.diagnostics());
        diagnostics.addAll(accepted.diagnostics());
        diagnostics.sort(Comparator.comparingInt((Diagnostic item) -> order.getOrDefault(item.itemId(), Integer.MAX_VALUE))
                .thenComparing(item -> Objects.toString(item.reason(), ""))
                .thenComparing(item -> Objects.toString(item.name(), ""))
                .thenComparing(item -> Objects.toString(item.evidence(), "")));
        batches.save(change(current, "DONE", current.attempt(), current.jobId(), null, null));
        Run updated = copy(run, run.status(), run.position() + current.size(), facts, diagnostics, null, run.error(),
                run.estimateStartedAt(), run.estimateStartPosition());
        runs.save(settled(updated));
    }

    @Transactional
    public void fail(Batch expected, String code, String message) {
        Run run = locked(expected.runId());
        Batch current = batch(expected.runId(), expected.index());
        if (!accepts(run, current, expected)) return;
        batches.save(change(current, "FAILED", current.attempt(), current.jobId(), code, message));
        runs.save(settled(copy(run, run.status(), run.position(), run.facts(), run.diagnostics(), null,
                run.error() == null ? message : run.error(), run.estimateStartedAt(), run.estimateStartPosition())));
    }

    @Transactional
    public Run stop(UUID id) {
        Run run = locked(id);
        if (Set.of("PAUSED", "STOPPING").contains(run.status())) return run;
        if (!"RUNNING".equals(run.status())) throw conflict("MARKET_ANALYSIS_NOT_STOPPABLE", "Этот анализ нельзя остановить.");
        Run stopped = settled(copy(run, "STOPPING", run.position(), run.facts(), run.diagnostics(), null, run.error(),
                run.estimateStartedAt(), run.estimateStartPosition()));
        runs.save(stopped);
        return stopped;
    }

    @Transactional
    public Run cancel(UUID id) {
        Run run = locked(id);
        if ("CANCELLED".equals(run.status())) return run;
        if (!Set.of("RUNNING", "STOPPING", "PAUSED").contains(run.status()))
            throw conflict("MARKET_ANALYSIS_NOT_CANCELLABLE", "Этот анализ нельзя отменить.");
        for (Batch batch : batches.list(id)) {
            if (!"DONE".equals(batch.status())) batches.save(change(batch, "CANCELLED", batch.attempt(), batch.jobId(), null, null));
        }
        Run cancelled = copy(run, "CANCELLED", run.position(), run.facts(), run.diagnostics(), null, null,
                run.estimateStartedAt(), run.estimateStartPosition());
        runs.save(cancelled);
        return cancelled;
    }

    @Transactional
    public Run resume(UUID id) {
        Run run = locked(id);
        if (!Set.of("PAUSED", "FAILED").contains(run.status()))
            throw conflict("MARKET_ANALYSIS_NOT_RESUMABLE", "Этот анализ нельзя продолжить.");
        for (Batch batch : batches.list(id)) {
            if ("FAILED".equals(batch.status())) {
                // Retain error/job identity: a resumed worker first recovers the same job/key.
                batches.save(change(batch, "READY", batch.attempt(), batch.jobId(), batch.errorCode(), batch.errorMessage()));
            }
        }
        Run resumed = copy(run, "RUNNING", run.position(), run.facts(), run.diagnostics(), null, null,
                clock.instant(), run.position());
        runs.save(resumed);
        return resumed;
    }

    @Transactional
    public void expire(UUID id) {
        Run run = locked(id);
        if (!Set.of("RUNNING", "STOPPING").contains(run.status())) return;
        String message = "Срок выполнения анализа истёк. Запустите новый анализ.";
        for (Batch batch : batches.list(id)) if (!"DONE".equals(batch.status())) {
            batches.save(change(batch, "FAILED", batch.attempt(), batch.jobId(), "MARKET_ANALYSIS_EXPIRED", message));
        }
        runs.save(copy(run, "FAILED", run.position(), run.facts(), run.diagnostics(), null, message,
                run.estimateStartedAt(), run.estimateStartPosition()));
    }

    public boolean accepts(UUID id) { return runs.acceptsBatchResults(id); }

    private Run settled(Run run) {
        List<Batch> all = batches.list(run.id());
        if (all.stream().allMatch(batch -> "DONE".equals(batch.status()))) {
            String markdown = MarketMarkdown.renderForAgent(run, MarketAggregation.aggregateIgnoreCase(run.sources(), run.facts()));
            return copy(run, "COMPLETED", run.position(), run.facts(), run.diagnostics(), markdown, null,
                    run.estimateStartedAt(), run.estimateStartPosition());
        }
        if (all.stream().noneMatch(Batch::active)) {
            if (run.error() != null) return copy(run, "FAILED", run.position(), run.facts(), run.diagnostics(), null,
                    run.error(), run.estimateStartedAt(), run.estimateStartPosition());
            if ("STOPPING".equals(run.status())) return copy(run, "PAUSED", run.position(), run.facts(), run.diagnostics(), null,
                    null, run.estimateStartedAt(), run.estimateStartPosition());
        }
        return run;
    }

    private Run locked(UUID id) { runs.lock(id); return load(id); }
    private Batch batch(UUID id, int index) {
        return batches.list(id).stream().filter(batch -> batch.index() == index).findFirst().orElseThrow();
    }
    private boolean accepts(Run run, Batch current, Batch expected) {
        return Set.of("RUNNING", "STOPPING").contains(run.status()) && current.active() && current.attempt() == expected.attempt();
    }
    private Batch change(Batch batch, String status, int attempt, UUID job, String code, String message) {
        return new Batch(batch.runId(), batch.index(), batch.start(), batch.size(), status, attempt, job, code, message);
    }
    private Run copy(Run run, String status, int position, List<Fact> facts, List<Diagnostic> diagnostics,
                     String markdown, String error, Instant estimateStarted, int estimatePosition) {
        return new Run(run.id(), run.resumeId(), run.resumeName(), run.resumeText(), run.createdAt(), status,
                "COMPLETED".equals(status) ? "DONE" : "EXTRACT", position, null, run.sources(), List.copyOf(facts),
                run.coverage(), List.copyOf(diagnostics), markdown, error, estimateStarted, estimatePosition, run.searchId(), run.searchName());
    }
    private ApplicationException conflict(String code, String message) {
        return new ApplicationException(ApplicationErrorCategory.CONFLICT, code, message);
    }
}
