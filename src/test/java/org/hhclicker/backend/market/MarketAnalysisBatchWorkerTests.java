package org.hhclicker.backend.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hhclicker.backend.market.MarketAnalysisBatchRepository.Batch;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketAnalysisBatchWorkerTests {
    private final MarketAnalysisBatchService state = mock(MarketAnalysisBatchService.class);
    private final MarketGenerationClient agent = mock(MarketGenerationClient.class);
    private final MarketAnalysisRepository.Run run = MarketBatchFixtures.run(UUID.randomUUID(), 8);
    private final UUID jobId = UUID.randomUUID();
    private final AtomicLong time = new AtomicLong();

    private MarketAnalysisBatchWorker worker() {
        when(state.load(run.id())).thenReturn(run);
        when(state.accepts(run.id())).thenReturn(true);
        return new MarketAnalysisBatchWorker(state, agent, time::get,
                () -> time.addAndGet(Duration.ofSeconds(1).toNanos()), Duration.ofSeconds(3));
    }

    private Batch batch(UUID job, String previousError) {
        return new Batch(run.id(), 0, 0, 8, job == null ? "SUBMITTING" : "SUBMITTED", 0, job, previousError, null);
    }

    private ApplicationException conflict() {
        return new ApplicationException(ApplicationErrorCategory.INTERNAL, "MARKET_AGENT_KEY_CONFLICT", "safe conflict");
    }

    @Test
    void initialKeyConflictFailsImmediatelyWithoutReplayingTheModel() throws Exception {
        Batch batch = batch(null, null);
        when(agent.extract(any(), eq(batch.key()))).thenThrow(conflict());
        worker().execute(batch, () -> false);
        verify(state).fail(batch, "MARKET_AGENT_KEY_CONFLICT", "safe conflict");
        verify(state, never()).retry(any());
        verify(agent, times(1)).extract(any(), anyString());
        assertEquals(0, time.get());
    }

    @Test
    void explicitResumeCreatesOnlyOneNewAttemptAfterAKeyConflict() throws Exception {
        Batch batch = batch(null, "MARKET_AGENT_KEY_CONFLICT");
        Batch retry = new Batch(run.id(), 0, 0, 8, "SUBMITTING", 1, null, null, null);
        when(state.retry(batch)).thenReturn(retry);
        when(agent.extract(any(), eq(batch.key()))).thenThrow(conflict());
        when(agent.extract(any(), eq(retry.key()))).thenThrow(conflict());
        worker().execute(batch, () -> false);
        verify(state).retry(batch);
        verify(state).fail(retry, "MARKET_AGENT_KEY_CONFLICT", "safe conflict");
        verify(agent, times(2)).extract(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GENERATION_AGENT_UNAVAILABLE", "MARKET_AGENT_KEY_CONFLICT"})
    void resumedConflictCanCompleteUnderTheNewDurableKey(String previousError) throws Exception {
        Batch batch = batch(null, previousError);
        Batch retry = new Batch(run.id(), 0, 0, 8, "SUBMITTING", 1, null, null, null);
        Batch submitted = new Batch(run.id(), 0, 0, 8, "SUBMITTED", 1, jobId, null, null);
        when(state.retry(batch)).thenReturn(retry);
        when(state.submitted(retry, jobId)).thenReturn(submitted);
        when(agent.extract(any(), eq(batch.key()))).thenThrow(conflict());
        when(agent.extract(any(), eq(retry.key()))).thenReturn(new MarketGenerationClient.SubmittedJob(jobId, "queued"));
        when(agent.extraction(jobId)).thenReturn(done());

        worker().execute(batch, () -> false);

        verify(state).complete(eq(submitted), any());
        verify(state, never()).fail(any(), anyString(), anyString());
        verify(agent, times(2)).extract(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RATE_LIMIT", "AUTH_REQUIRED", "TIMEOUT", "MODEL_UNAVAILABLE", "RESOURCE_EXHAUSTED", "unknown-private-code"})
    void failedJobsKeepWhitelistedReasonsWithoutCopyingAgentMessages(String code) throws Exception {
        Batch batch = batch(jobId, null);
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId, "failed", null,
                new MarketGenerationClient.AgentError(code, "private-agent-message-token")));
        worker().execute(batch, () -> false);
        var message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(state).fail(eq(batch), eq(code.startsWith("unknown") ? "MARKET_AGENT_FAILED" : code), message.capture());
        assertFalse(message.getValue().contains("private"));
        verify(state, never()).retry(any());
    }

    @Test
    void unavailableAgentExpiresWithoutAnyRealWaiting() throws Exception {
        Batch batch = batch(jobId, null);
        when(agent.extraction(jobId)).thenThrow(new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM,
                "GENERATION_AGENT_UNAVAILABLE", "safe"));
        worker().execute(batch, () -> false);
        verify(state).fail(eq(batch), eq("MARKET_AGENT_TIMEOUT"), anyString());
        verify(agent, times(4)).extraction(jobId);
    }

    @Test
    void runningJobExpiresDespiteSuccessfulPolling() throws Exception {
        Batch batch = batch(jobId, null);
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId, "running", null, null));
        worker().execute(batch, () -> false);
        verify(state).fail(eq(batch), eq("MARKET_AGENT_TIMEOUT"), anyString());
    }

    @Test
    void queuedTimeDoesNotConsumeExecutionDeadline() throws Exception {
        Batch batch = batch(jobId, null);
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId, "queued", null, null));
        worker().execute(batch, () -> time.get() >= Duration.ofSeconds(10).toNanos());
        verify(state, never()).fail(any(), anyString(), anyString());
        assertEquals(Duration.ofSeconds(10).toNanos(), time.get());
    }

    @Test
    void persistenceFailureIsNotConvertedIntoAnAgentFailure() throws Exception {
        Batch batch = batch(jobId, null);
        when(agent.extraction(jobId)).thenReturn(done());
        doThrow(new IllegalStateException("synthetic DB failure")).when(state).complete(eq(batch), any());
        assertThrows(IllegalStateException.class, () -> worker().execute(batch, () -> false));
        verify(state, never()).fail(any(), anyString(), anyString());
        verify(agent, never()).extract(any(), anyString());
    }

    private MarketGenerationClient.ExtractionJob done() {
        var items = run.sources().stream().map(source -> new MarketGenerationClient.ExtractedVacancy(
                source.id(), java.util.List.<MarketGenerationClient.ExtractedFact>of())).toList();
        return new MarketGenerationClient.ExtractionJob(jobId, "done", new MarketGenerationClient.ExtractionResult(items), null);
    }
}
