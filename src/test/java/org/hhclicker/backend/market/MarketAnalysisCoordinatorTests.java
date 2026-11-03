package org.hhclicker.backend.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hhclicker.backend.market.MarketAnalysisBatchRepository.Batch;
import org.hhclicker.backend.market.MarketAnalysisRepository.Run;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketAnalysisCoordinatorTests {
    private final MarketGenerationClient agent = mock(MarketGenerationClient.class);

    @Test
    void virtualWorkersKeepAFourBatchRollingWindowAndAcceptOutOfOrderResults() throws Exception {
        Fixture fixture = new Fixture(40);
        var started = new LinkedBlockingQueue<Integer>();
        var releases = IntStream.range(0, 5).mapToObj(index -> new CountDownLatch(1)).toList();
        when(agent.extract(any(), anyString())).thenAnswer(call -> {
            int index = Integer.parseInt(((String) call.getArgument(1)).split("-extract-")[1].split("-attempt-")[0]);
            assertTrue(Thread.currentThread().isVirtual());
            started.add(index);
            assertTrue(releases.get(index).await(5, TimeUnit.SECONDS));
            return fixture.submission(index);
        });
        when(agent.extraction(any())).thenAnswer(call -> fixture.done(call.getArgument(0)));
        try (var coordinator = new ClosingCoordinator(fixture.state, agent)) {
            coordinator.value.advance(fixture.run.id());
            var first = new java.util.HashSet<Integer>();
            for (int index = 0; index < 4; index++) first.add(started.poll(3, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(0, 1, 2, 3), first);
            assertNull(started.poll(100, TimeUnit.MILLISECONDS));
            releases.get(2).countDown();
            assertEquals(4, started.poll(3, TimeUnit.SECONDS));
            releases.forEach(CountDownLatch::countDown);
            assertTrue(fixture.completed.await(5, TimeUnit.SECONDS));
            assertEquals(40, fixture.accepted.values().stream().mapToInt(value -> value.facts().size()).sum());
            verify(agent, times(5)).extract(any(), anyString());
        } finally { releases.forEach(CountDownLatch::countDown); }
    }

    @Test
    void restartPollsCommittedJobWithoutSubmittingItAgain() throws Exception {
        Fixture fixture = new Fixture(8);
        fixture.batches.put(0, new Batch(fixture.run.id(), 0, 0, 8, "SUBMITTED", 0, fixture.ids.getFirst(), null, null));
        when(agent.extraction(any())).thenAnswer(call -> fixture.done(call.getArgument(0)));
        try (var coordinator = new ClosingCoordinator(fixture.state, agent)) {
            coordinator.value.advance(fixture.run.id());
            assertTrue(fixture.completed.await(3, TimeUnit.SECONDS));
            verify(agent, never()).extract(any(), anyString());
        }
    }

    @Test
    void restartRecoversAnUncertainSubmissionUsingTheOriginalKey() throws Exception {
        Fixture fixture = new Fixture(8);
        Batch pending = new Batch(fixture.run.id(), 0, 0, 8, "SUBMITTING", 3, null, null, null);
        fixture.batches.put(0, pending);
        when(agent.extract(any(), eq(pending.key()))).thenReturn(fixture.submission(0));
        when(agent.extraction(any())).thenAnswer(call -> fixture.done(call.getArgument(0)));
        try (var coordinator = new ClosingCoordinator(fixture.state, agent)) {
            coordinator.value.advance(fixture.run.id());
            assertTrue(fixture.completed.await(3, TimeUnit.SECONDS));
            verify(agent).extract(any(), eq(pending.key()));
            verify(fixture.state, never()).retry(any());
        }
    }

    @Test
    void oneFailedAgentJobDoesNotAutomaticallyCreateAnotherModelCall() throws Exception {
        Fixture fixture = new Fixture(8);
        CountDownLatch failed = new CountDownLatch(1);
        when(agent.extract(any(), anyString())).thenReturn(fixture.submission(0));
        when(agent.extraction(any())).thenReturn(new MarketGenerationClient.ExtractionJob(fixture.ids.getFirst(),
                "failed", null, new MarketGenerationClient.AgentError("RATE_LIMIT", "not logged")));
        doAnswer(call -> {
            Batch batch = call.getArgument(0);
            fixture.batches.put(batch.index(), new Batch(batch.runId(), batch.index(), batch.start(), batch.size(),
                    "FAILED", batch.attempt(), batch.jobId(), call.getArgument(1), call.getArgument(2)));
            failed.countDown();
            return null;
        }).when(fixture.state).fail(any(), anyString(), anyString());
        try (var coordinator = new ClosingCoordinator(fixture.state, agent)) {
            coordinator.value.advance(fixture.run.id());
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            verify(agent, times(1)).extract(any(), anyString());
            verify(fixture.state, never()).retry(any());
            assertTrue(fixture.accepted.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "wrong-id", "invalid-result" })
    void invalidDoneIsNotAppliedAndWaitsForExplicitResume(String invalidity) throws Exception {
        Fixture fixture = new Fixture(8);
        UUID id = fixture.ids.getFirst();
        CountDownLatch failed = new CountDownLatch(1);
        when(agent.extract(any(), anyString())).thenReturn(fixture.submission(0));
        when(agent.extraction(id)).thenAnswer(call -> new MarketGenerationClient.ExtractionJob(
                "wrong-id".equals(invalidity) ? UUID.randomUUID() : id, "done",
                "invalid-result".equals(invalidity) ? null : fixture.done(id).result(), null));
        doAnswer(call -> { failed.countDown(); return null; })
                .when(fixture.state).fail(any(), eq("MARKET_AGENT_INVALID_RESPONSE"), anyString());
        try (var coordinator = new ClosingCoordinator(fixture.state, agent)) {
            coordinator.value.advance(fixture.run.id());
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            verify(fixture.state, never()).complete(any(), any());
            verify(fixture.state, never()).retry(any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "failed", "invalid" })
    void explicitResumePermitsOneNewAttemptForAnUnusableTerminalJob(String terminal) throws Exception {
        Fixture fixture = new Fixture(8);
        UUID oldId = fixture.ids.getFirst(), newId = UUID.randomUUID();
        Batch previous = new Batch(fixture.run.id(), 0, 0, 8, "READY", 0, oldId, "PREVIOUS_FAILURE", "failed");
        fixture.batches.put(0, previous);
        when(agent.extraction(oldId)).thenReturn(new MarketGenerationClient.ExtractionJob(oldId,
                "failed".equals(terminal) ? "failed" : "done", null, null));
        when(fixture.state.retry(any())).thenAnswer(call -> {
            Batch batch = call.getArgument(0);
            Batch retry = new Batch(batch.runId(), batch.index(), batch.start(), batch.size(), "SUBMITTING",
                    batch.attempt() + 1, null, null, null);
            fixture.batches.put(batch.index(), retry);
            return retry;
        });
        when(agent.extract(any(), anyString())).thenReturn(new MarketGenerationClient.SubmittedJob(newId, "queued"));
        when(agent.extraction(newId)).thenAnswer(call -> new MarketGenerationClient.ExtractionJob(newId,
                "done", fixture.done(oldId).result(), null));
        try (var coordinator = new ClosingCoordinator(fixture.state, agent)) {
            coordinator.value.advance(fixture.run.id());
            assertTrue(fixture.completed.await(3, TimeUnit.SECONDS));
            verify(fixture.state, times(1)).retry(any());
            verify(agent).extract(any(), eq("market-" + fixture.run.id() + "-extract-0-attempt-1"));
        }
    }

    private static final class ClosingCoordinator implements AutoCloseable {
        final MarketAnalysisCoordinator value;
        ClosingCoordinator(MarketAnalysisBatchService state, MarketGenerationClient agent) {
            value = new MarketAnalysisCoordinator(state, agent, java.time.Clock.systemUTC());
        }
        public void close() { value.close(); }
    }

    private static final class Fixture {
        final MarketAnalysisBatchService state = mock(MarketAnalysisBatchService.class);
        final Run run;
        final List<UUID> ids;
        final ConcurrentHashMap<Integer, Batch> batches = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, MarketExtractionResults.Accepted> accepted = new ConcurrentHashMap<>();
        final CountDownLatch completed;

        Fixture(int count) {
            run = MarketBatchFixtures.run(UUID.randomUUID(), count);
            ids = IntStream.range(0, (count + 7) / 8).mapToObj(index -> UUID.randomUUID()).toList();
            completed = new CountDownLatch(ids.size());
            for (int index = 0; index < ids.size(); index++) batches.put(index,
                    new Batch(run.id(), index, index * 8, Math.min(8, count - index * 8), "READY", 0, null, null, null));
            when(state.load(run.id())).thenReturn(run);
            when(state.accepts(run.id())).thenReturn(true);
            when(state.list(run.id())).thenAnswer(call -> batches.values().stream()
                    .sorted(java.util.Comparator.comparingInt(Batch::index)).toList());
            when(state.claim(eq(run.id()), anyInt())).thenAnswer(call -> {
                int index = call.getArgument(1);
                Batch batch = batches.get(index);
                if ("DONE".equals(batch.status()) || "FAILED".equals(batch.status())) return null;
                Batch claimed = new Batch(run.id(), index, batch.start(), batch.size(), "SUBMITTING",
                        batch.attempt(), batch.jobId(), batch.errorCode(), batch.errorMessage());
                batches.put(index, claimed);
                return claimed;
            });
            when(state.submitted(any(), any())).thenAnswer(call -> {
                Batch batch = call.getArgument(0);
                Batch submitted = new Batch(run.id(), batch.index(), batch.start(), batch.size(), "SUBMITTED",
                        batch.attempt(), call.getArgument(1), null, null);
                batches.put(batch.index(), submitted);
                return submitted;
            });
            doAnswer(call -> {
                Batch batch = call.getArgument(0);
                accepted.put(batch.index(), call.getArgument(1));
                batches.put(batch.index(), new Batch(run.id(), batch.index(), batch.start(), batch.size(), "DONE",
                        batch.attempt(), batch.jobId(), null, null));
                completed.countDown();
                return null;
            }).when(state).complete(any(), any());
        }
        MarketGenerationClient.SubmittedJob submission(int index) {
            return new MarketGenerationClient.SubmittedJob(ids.get(index), "queued");
        }
        MarketGenerationClient.ExtractionJob done(UUID id) {
            int index = ids.indexOf(id);
            Batch batch = batches.get(index);
            var items = run.sources().subList(batch.start(), batch.start() + batch.size()).stream().map(source ->
                    new MarketGenerationClient.ExtractedVacancy(source.id(), List.of(
                            new MarketGenerationClient.ExtractedFact("Java", "TECHNOLOGY", "Java")))).toList();
            return new MarketGenerationClient.ExtractionJob(id, "done", new MarketGenerationClient.ExtractionResult(items), null);
        }
    }
}
