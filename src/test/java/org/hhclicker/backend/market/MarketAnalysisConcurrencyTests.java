package org.hhclicker.backend.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hhclicker.backend.market.MarketAnalysisRepository.Run;
import org.hhclicker.backend.market.MarketAnalysisRepository.Source;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketAnalysisConcurrencyTests {
    private final MarketAnalysisRepository repository = mock(MarketAnalysisRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final MarketGenerationClient agent = mock(MarketGenerationClient.class);
    private final MarketAnalysisCoordinator coordinator = mock(MarketAnalysisCoordinator.class);
    private final MarketAnalysisService service = new MarketAnalysisService(repository, resumes, agent, coordinator);
    private final Map<UUID, Run> stored = new ConcurrentHashMap<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void persistence() {
        // Legacy-run fixture: no batch ownership; new-run registration is delegated explicitly.
        doAnswer(call -> { repository.insert(call.getArgument(0)); return null; })
                .when(coordinator).initialize(any());
        when(repository.find(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.active()).thenAnswer(call -> stored.values().stream()
                .filter(run -> List.of("RUNNING", "STOPPING").contains(run.status())).toList());
        doAnswer(call -> {
            Run run = call.getArgument(0);
            stored.put(run.id(), run);
            return null;
        }).when(repository).save(any());
        doAnswer(call -> { Run run = call.getArgument(0); stored.put(run.id(), run); return null; })
                .when(repository).insert(any());
        when(repository.hasOpen(any(), any())).thenAnswer(call -> stored.values().stream().anyMatch(run ->
                run.resumeId().equals(call.getArgument(0)) && !run.id().equals(call.getArgument(1))
                        && List.of("RUNNING", "STOPPING", "PAUSED").contains(run.status())));
    }

    @Test
    void cancelledPollCannotPublishSuccessAndDoesNotBlockAnotherAnalysis() throws Exception {
        Run run = run("RUNNING", UUID.randomUUID(), 9);
        Run other = run("PAUSED", null, 2);
        Run fresh = run("RUNNING", null, 2);
        stubResume(fresh.resumeId());
        when(repository.sources(fresh.resumeId())).thenReturn(fresh.sources());
        stored.put(run.id(), run);
        stored.put(other.id(), other);
        when(agent.extraction(run.jobId())).thenAnswer(call -> {
            block();
            return done(run);
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var advance = executor.submit(service::advanceActive);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("CANCELLED", executor.submit(() -> service.cancel(run.id())).get(2, TimeUnit.SECONDS).status());
                assertEquals("RUNNING", executor.submit(() -> service.resume(other.id())).get(2, TimeUnit.SECONDS).status());
                assertEquals("RUNNING", executor.submit(() -> service.start(fresh.resumeId())).get(2, TimeUnit.SECONDS).status());
            } finally {
                release.countDown();
            }
            advance.get(3, TimeUnit.SECONDS);
        }
        assertEquals("CANCELLED", stored.get(run.id()).status());
        assertEquals(0, stored.get(run.id()).position());
        assertTrue(stored.get(run.id()).facts().isEmpty());
    }

    @Test
    void stopDuringSubmissionRetainsJobAndStopsAfterCurrentBatch() throws Exception {
        Run run = run("RUNNING", null, 9);
        UUID job = UUID.randomUUID();
        stored.put(run.id(), run);
        when(agent.extract(any(), anyString())).thenAnswer(call -> {
            block();
            return new MarketGenerationClient.SubmittedJob(job, "queued");
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var advance = executor.submit(service::advanceActive);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("STOPPING", executor.submit(() -> service.stop(run.id())).get(2, TimeUnit.SECONDS).status());
                executor.submit(service::advanceActive).get(2, TimeUnit.SECONDS);
                verify(agent, times(1)).extract(any(), anyString());
            } finally {
                release.countDown();
            }
            advance.get(3, TimeUnit.SECONDS);
        }
        Run submitted = stored.get(run.id());
        assertEquals("STOPPING", submitted.status());
        assertEquals(job, submitted.jobId());
        when(agent.extraction(job)).thenReturn(done(submitted));
        service.advanceActive();
        assertEquals("PAUSED", stored.get(run.id()).status());
        assertEquals(8, stored.get(run.id()).position());
        verify(agent, times(1)).extract(any(), anyString());
    }

    @Test
    void stopDuringLastPollCompletesTheReport() throws Exception {
        Run run = run("RUNNING", UUID.randomUUID(), 2);
        stored.put(run.id(), run);
        when(agent.extraction(run.jobId())).thenAnswer(call -> { block(); return done(run); });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var advance = executor.submit(service::advanceActive);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("STOPPING", executor.submit(() -> service.stop(run.id())).get(2, TimeUnit.SECONDS).status());
            } finally { release.countDown(); }
            advance.get(3, TimeUnit.SECONDS);
        }
        assertEquals("COMPLETED", stored.get(run.id()).status());
        assertNotNull(stored.get(run.id()).markdown());
    }

    @Test
    void cancellationDiscardsLateInvalidResponsesAsWellAsSuccessfulOnes() throws Exception {
        Run run = run("RUNNING", UUID.randomUUID(), 2);
        stored.put(run.id(), run);
        when(agent.extraction(run.jobId())).thenAnswer(call -> { block(); return null; });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var advance = executor.submit(service::advanceActive);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                executor.submit(() -> service.cancel(run.id())).get(2, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            advance.get(3, TimeUnit.SECONDS);
        }
        assertEquals("CANCELLED", stored.get(run.id()).status());
        assertNull(stored.get(run.id()).error());
    }

    @Test
    void lateResumeCannotOverwriteNewerStateOrBlockOtherControls() throws Exception {
        Run other = run("PAUSED", null, 2);
        stored.put(other.id(), other);
        Run failed = run("FAILED", UUID.randomUUID(), 2);
        stubResume(failed.resumeId());
        stored.put(failed.id(), failed);
        when(agent.extraction(failed.jobId())).thenAnswer(call -> { block(); return null; });
        when(agent.extract(any(), anyString())).thenReturn(new MarketGenerationClient.SubmittedJob(UUID.randomUUID(), "queued"));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var resume = executor.submit(() -> service.resume(failed.id()));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("CANCELLED", executor.submit(() -> service.cancel(other.id())).get(2, TimeUnit.SECONDS).status());
                assertEquals("MARKET_ANALYSIS_ALREADY_RUNNING",
                        assertThrows(ApplicationException.class, () -> service.resume(failed.id())).machineCode());
                assertEquals("MARKET_ANALYSIS_ALREADY_RUNNING",
                        assertThrows(ApplicationException.class, () -> service.start(failed.resumeId())).machineCode());
                // Simulate a newer persisted terminal state while an external request is in flight.
                stored.put(failed.id(), copyStatus(failed, "CANCELLED"));
                assertThrows(ApplicationException.class, () -> service.resume(failed.id()));
            } finally { release.countDown(); }
            assertEquals("CANCELLED", resume.get(3, TimeUnit.SECONDS).status());
        }
        assertEquals("CANCELLED", stored.get(failed.id()).status());
    }

    @Test
    void persistenceFailureIsNotReclassifiedAndReleasesOperation() {
        Run run = run("RUNNING", UUID.randomUUID(), 2);
        stored.put(run.id(), run);
        when(agent.extraction(run.jobId())).thenReturn(done(run));
        RuntimeException failure = new IllegalStateException("Synthetic database failure");
        doThrow(failure).doAnswer(call -> { Run next = call.getArgument(0); stored.put(next.id(), next); return null; })
                .when(repository).save(any());
        assertSame(failure, assertThrows(RuntimeException.class, service::advanceActive));
        assertEquals("RUNNING", stored.get(run.id()).status());
        service.advanceActive();
        service.advanceActive();
        assertEquals("COMPLETED", stored.get(run.id()).status());
        verify(repository, never()).save(argThat(next -> "FAILED".equals(next.status())));
    }

    private void block() throws InterruptedException {
        entered.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS), "External request was not released");
    }

    @Test
    void transientNetworkFailurePreservesTheJobForTheNextPoll() {
        Run run = run("RUNNING", UUID.randomUUID(), 2);
        stored.put(run.id(), run);
        when(agent.extraction(run.jobId())).thenThrow(new ApplicationException(
                org.hhclicker.backend.shared.exception.ApplicationErrorCategory.EXTERNAL_SYSTEM,
                "GENERATION_AGENT_UNAVAILABLE", "Synthetic network timeout")).thenReturn(done(run));

        service.advanceActive();
        assertEquals(run, stored.get(run.id()));
        service.advanceActive();
        assertEquals(2, stored.get(run.id()).position());
        assertEquals("RUNNING", stored.get(run.id()).status());
    }

    private Run run(String status, UUID job, int count) {
        List<Source> sources = IntStream.range(0, count).mapToObj(index -> new Source(UUID.randomUUID(),
                Integer.toString(index + 1), "Java", "id:1", "Synthetic company", "Java", Instant.now())).toList();
        return new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(), status,
                "EXTRACT", 0, job, sources, List.of(), List.of(), null, null);
    }

    private void stubResume(UUID id) {
        when(resumes.findById(id)).thenReturn(Optional.of(new HhResume(id, UUID.randomUUID(), "hh-1", "Java",
                null, Instant.now(), Instant.now(), "Java", Instant.now())));
    }

    private Run copyStatus(Run run, String status) {
        return new Run(run.id(), run.resumeId(), run.resumeName(), run.resumeText(), run.createdAt(), status,
                run.phase(), run.position(), run.jobId(), run.sources(), run.facts(), run.coverage(), null, null);
    }

    private MarketGenerationClient.ExtractionJob done(Run run) {
        return new MarketGenerationClient.ExtractionJob(run.jobId(), "done",
                new MarketGenerationClient.ExtractionResult(run.sources().stream().limit(8)
                        .map(source -> new MarketGenerationClient.ExtractedVacancy(source.id(), List.of())).toList()), null);
    }
}
