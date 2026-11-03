package org.hhclicker.backend.market;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.hhclicker.backend.market.MarketAnalysisBatchRepository.Batch;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small rolling window; the agent enforces the shared analysis/memory limit. */
@Component
public class MarketAnalysisCoordinator {
    private static final Logger log = LoggerFactory.getLogger(MarketAnalysisCoordinator.class);
    private static final int WINDOW = 4;
    private final MarketAnalysisBatchService state;
    private final MarketAnalysisBatchWorker processor;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public MarketAnalysisCoordinator(MarketAnalysisBatchService state, MarketGenerationClient agent, Clock clock) {
        this.state = state;
        this.processor = new MarketAnalysisBatchWorker(state, agent);
        this.clock = clock;
    }

    public boolean owns(UUID id) { return state.owns(id); }
    public void initialize(MarketAnalysisRepository.Run run) { state.initialize(run); }
    public MarketAnalysisRepository.Run stop(UUID id) { return state.stop(id); }
    public MarketAnalysisRepository.Run cancel(UUID id) { return state.cancel(id); }
    public MarketAnalysisRepository.Run resume(UUID id) { return state.resume(id); }

    public synchronized void advance(UUID id) {
        if (closed) return;
        var run = state.load(id);
        if (!Set.of("RUNNING", "STOPPING").contains(run.status())) return;
        if (Duration.between(run.createdAt(), clock.instant()).toDays() >= 7) {
            state.expire(id);
            return;
        }
        var batches = state.list(id);
        // Recover committed work before admitting new batches after a restart.
        var candidates = batches.stream().filter(Batch::active).toList();
        for (Batch batch : candidates) dispatch(batch);
        if (!"RUNNING".equals(run.status()) || run.error() != null) return;
        for (Batch batch : batches) {
            if (active.stream().filter(key -> key.startsWith(id + "/")).count() >= WINDOW) break;
            if ("READY".equals(batch.status())) dispatch(batch);
        }
    }

    private void dispatch(Batch batch) {
        String token = batch.runId() + "/" + batch.index();
        if (!active.add(token)) return;
        workers.submit(() -> {
            boolean checkpointed = false;
            try {
                Batch claimed = state.claim(batch.runId(), batch.index());
                if (claimed != null) processor.execute(claimed, () -> closed);
                checkpointed = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                // Persistence failures keep the checkpoint retryable. Never turn them into agent failures.
                log.warn("Market batch checkpoint deferred: run={}, batch={}, type={}",
                        batch.runId(), batch.index(), failure.getClass().getSimpleName());
            } finally {
                active.remove(token);
                if (checkpointed && !closed) advance(batch.runId());
            }
        });
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        workers.shutdownNow();
    }
}
