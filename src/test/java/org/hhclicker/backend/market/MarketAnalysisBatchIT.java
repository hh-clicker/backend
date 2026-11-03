package org.hhclicker.backend.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.hhclicker.backend.market.MarketAnalysisBatchRepository.Batch;
import org.hhclicker.backend.market.MarketAnalysisRepository.Run;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Production Liquibase schema and transactional proxy; no scheduler or external calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MarketAnalysisBatchIT extends PostgresIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private MarketAnalysisBatchService state;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private MarketAnalysisService scheduler;
    private UUID resume;

    @BeforeEach
    void fixtures() {
        var fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        resume = fixtures.workspace().resumeId();
    }

    @Test
    void concurrentOutOfOrderCompletionDoesNotLoseFactsOrCountTheSameBatchTwice() throws Exception {
        Run run = create(17);
        Batch first = submitted(run, 0), second = submitted(run, 1), last = submitted(run, 2);
        state.complete(last, MarketBatchFixtures.facts(run, 16, 1));
        assertEquals(1, state.load(run.id()).position());
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { assertTrue(release.await(3, TimeUnit.SECONDS));
                state.complete(first, MarketBatchFixtures.facts(run, 0, 8)); return null; });
            var two = executor.submit(() -> { assertTrue(release.await(3, TimeUnit.SECONDS));
                state.complete(second, MarketBatchFixtures.facts(run, 8, 8)); return null; });
            release.countDown();
            one.get(5, TimeUnit.SECONDS); two.get(5, TimeUnit.SECONDS);
        }
        state.complete(first, MarketBatchFixtures.facts(run, 0, 8));
        Run done = state.load(run.id());
        assertEquals("COMPLETED", done.status());
        assertEquals(17, done.position());
        assertEquals(run.sources().stream().map(MarketAnalysisRepository.Source::id).toList(),
                done.facts().stream().map(MarketAnalysisRepository.Fact::vacancyId).toList());
        assertNotNull(done.markdown());
    }

    @Test
    void stoppingDrainsClaimedBatchesAndResumingClaimsOnlyRemainingWork() {
        Run run = create(24);
        Batch first = submitted(run, 0), second = submitted(run, 1);
        assertEquals("STOPPING", state.stop(run.id()).status());
        assertNull(state.claim(run.id(), 2));
        state.complete(second, MarketBatchFixtures.facts(run, 8, 8));
        state.complete(first, MarketBatchFixtures.facts(run, 0, 8));
        assertEquals("PAUSED", state.load(run.id()).status());
        assertEquals(16, state.resume(run.id()).position());
        assertNull(state.claim(run.id(), 0));
        assertNotNull(state.claim(run.id(), 2));
    }

    @Test
    void cancellationDiscardsLateResults() {
        Run run = create(16);
        Batch first = submitted(run, 0);
        state.cancel(run.id());
        state.complete(first, MarketBatchFixtures.facts(run, 0, 8));
        state.fail(first, "LATE", "late");
        assertEquals("CANCELLED", state.load(run.id()).status());
        assertEquals(0, state.load(run.id()).position());
        assertTrue(state.load(run.id()).facts().isEmpty());
        assertTrue(state.list(run.id()).stream().allMatch(batch -> "CANCELLED".equals(batch.status())));
    }

    @Test
    void failedBatchStopsAdmissionButSuccessfulPeersAreKeptForResume() {
        Run run = create(24);
        Batch first = submitted(run, 0), second = submitted(run, 1);
        state.fail(first, "MARKET_AGENT_FAILED", "failed");
        assertNull(state.claim(run.id(), 2));
        state.complete(second, MarketBatchFixtures.facts(run, 8, 8));
        assertEquals("FAILED", state.load(run.id()).status());
        state.resume(run.id());
        Batch recovered = state.claim(run.id(), 0);
        assertEquals(first.jobId(), recovered.jobId());
        assertEquals(first.key(), recovered.key());
        Batch retry = state.retry(recovered);
        assertEquals(first.attempt() + 1, retry.attempt());
        assertNotEquals(first.key(), retry.key());
        assertNull(state.claim(run.id(), 1));
        assertEquals(8, state.load(run.id()).position());
        // A stale attempt cannot apply a late answer after the retry was reserved.
        state.complete(first, MarketBatchFixtures.facts(run, 0, 8));
        assertEquals(8, state.load(run.id()).position());
    }

    @Test
    void rollingBackACheckpointRestoresBothBatchAndRoot() {
        Run run = create(8);
        Batch first = submitted(run, 0);
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            state.complete(first, MarketBatchFixtures.facts(run, 0, 8));
            throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals("SUBMITTED", state.list(run.id()).getFirst().status());
        assertEquals(0, state.load(run.id()).position());
        assertTrue(state.load(run.id()).facts().isEmpty());
        state.complete(first, MarketBatchFixtures.facts(run, 0, 8));
        assertEquals("COMPLETED", state.load(run.id()).status());
    }

    @Test
    void stoppingAResumedFailureCannotLeaveItClaimedForever() {
        Run run = create(16);
        Batch first = submitted(run, 0);
        state.fail(first, "MARKET_AGENT_FAILED", "failed");
        state.resume(run.id());
        Batch recovered = state.claim(run.id(), 0);
        state.stop(run.id());
        assertNull(state.retry(recovered));
        state.fail(recovered, "MARKET_AGENT_FAILED", "failed");
        assertEquals("FAILED", state.load(run.id()).status());
        assertTrue(state.list(run.id()).stream().noneMatch(Batch::active));
    }

    @Test
    void pollingReadsOnlyTheRunStateAndRejectsCancelledRuns() {
        Run run = create(8);
        assertTrue(state.accepts(run.id()));
        state.cancel(run.id());
        assertFalse(state.accepts(run.id()));
        assertFalse(state.accepts(UUID.randomUUID()));
    }

    @Test
    void persistentClaimsRecoverTheSameJobAndSubmissionKey() {
        Run run = create(16);
        Batch sending = state.claim(run.id(), 0);
        assertEquals(sending, state.claim(run.id(), 0));
        Batch accepted = state.submitted(sending, UUID.randomUUID());
        assertEquals(accepted, state.claim(run.id(), 0));
        assertEquals(sending.key(), accepted.key());
        assertEquals(List.of(8, 8), state.list(run.id()).stream().map(Batch::size).toList());
    }

    private Run create(int count) {
        Run run = MarketBatchFixtures.run(resume, count);
        state.initialize(run);
        return state.load(run.id());
    }
    private Batch submitted(Run run, int index) {
        return state.submitted(state.claim(run.id(), index), UUID.randomUUID());
    }
}
