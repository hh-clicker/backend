package org.hhclicker.backend.vacancy.relevance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.support.MutableClock;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParallelVacancyRelevanceServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    private final UUID resumeId = UUID.randomUUID();
    private final VacancyRepository vacancies = mock(VacancyRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final GenerationAgentRelevanceClient agent = mock(GenerationAgentRelevanceClient.class);
    private final VacancyRelevancePersistenceService persistence = mock(VacancyRelevancePersistenceService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final TrackingExecutor executor = new TrackingExecutor();
    private final Map<UUID, GenerationAgentRelevanceClient.GenerationRequest> jobs = new ConcurrentHashMap<>();
    private ParallelVacancyRelevanceService service;
    private VacancyRelevanceService legacy;

    @BeforeEach
    void setUp() {
        legacy = new VacancyRelevanceService(vacancies, resumes, agent, persistence, clock);
        service = new ParallelVacancyRelevanceService(vacancies, resumes, agent, persistence, legacy,
                clock, executor, Duration.ofMillis(1), Duration.ofSeconds(10));
        HhResume resume = mock(HhResume.class);
        when(resume.accountId()).thenReturn(UUID.randomUUID());
        when(resume.name()).thenReturn("Java");
        when(resume.plainText()).thenReturn("Java, Spring");
        when(resumes.findById(resumeId)).thenReturn(Optional.of(resume));
        when(agent.submit(any(), anyString())).thenAnswer(call -> {
            UUID id = UUID.randomUUID();
            jobs.put(id, call.getArgument(0));
            return new GenerationAgentRelevanceClient.SubmittedJob(id, "queued");
        });
        when(agent.find(any())).thenAnswer(call -> done(call.getArgument(0)));
    }

    @AfterEach
    void stop() { service.stop(); }

    @Test
    void fiveHundredVacanciesSubmitFiftyIndependentBatchesOnVirtualThreads() throws Exception {
        var cards = cards(500, false);
        var entered = new CountDownLatch(50);
        var release = new CountDownLatch(1);
        var keys = ConcurrentHashMap.<String>newKeySet();
        var virtualCalls = new AtomicInteger();
        doAnswer(call -> {
            if (Thread.currentThread().isVirtual()) virtualCalls.incrementAndGet();
            keys.add(call.getArgument(1));
            UUID id = UUID.randomUUID();
            GenerationAgentRelevanceClient.GenerationRequest input = call.getArgument(0);
            assertEquals(10, input.vacancies().size());
            jobs.put(id, input);
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new GenerationAgentRelevanceClient.SubmittedJob(id, "queued");
        }).when(agent).submit(any(), anyString());

        var run = service.start(request(cards, false));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(50, virtualCalls.get());
            assertEquals(50, keys.size());
            assertEquals(0, service.find(run.id()).completedBatches());
            assertEquals("VACANCY_RELEVANCE_ALREADY_RUNNING", assertThrows(ApplicationException.class,
                    () -> legacy.start(new VacancyRelevanceRequest(List.of(cards.getFirst().id())))).machineCode());
        } finally { release.countDown(); }
        executor.drain();
        var result = service.find(run.id());
        assertEquals("done", result.status());
        assertEquals(500, result.evaluatedCount());
        assertEquals(50, result.completedBatches());
        verify(persistence, times(50)).save(anyMap());
    }

    @Test
    void benchmarkIncludesAlreadyRatedCardsWithoutWritingOrSendingNotifications() throws Exception {
        var cards = cards(25, true);
        var run = service.start(request(cards, true));
        executor.drain();
        assertEquals(25, service.find(run.id()).evaluatedCount());
        assertTrue(service.find(run.id()).benchmark());
        assertNull(service.active(resumeId));
        assertTrue(service.notificationRuns().isEmpty());
        verifyNoInteractions(persistence);
    }

    @Test
    void normalRunOnlyGeneratesAndSavesUnratedCards() throws Exception {
        var cards = cards(5, 3);
        var run = service.start(new ParallelVacancyRelevanceRequest(cards.stream().map(Vacancy::id).toList(), 5, 3, false));
        executor.drain();
        assertEquals(2, service.find(run.id()).requestedCount());
        assertEquals(2, service.find(run.id()).evaluatedCount());
        assertEquals(List.of("3", "4"), jobs.values().iterator().next().vacancies().stream()
                .map(GenerationAgentRelevanceClient.VacancyInput::hhId).toList());
        verify(persistence).save(argThat(values -> values.keySet().equals(Set.of(cards.get(3).id(), cards.get(4).id()))));
    }

    @Test
    void failedBatchDoesNotDiscardSuccessfulBatchOrKeepTheResumeReserved() throws Exception {
        var cards = cards(20, false);
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            if (jobs.get(id).vacancies().getFirst().hhId().equals("0")) {
                return new GenerationAgentRelevanceClient.GenerationJob(id, "failed", null,
                        new GenerationAgentRelevanceClient.GenerationError("TEST_FAILURE", "Failure"));
            }
            return done(id);
        }).when(agent).find(any());
        var run = service.start(request(cards, false));
        executor.drain();
        var result = service.find(run.id());
        assertEquals("failed", result.status());
        assertEquals(10, result.evaluatedCount());
        assertEquals(10, result.missingCount());
        verify(persistence).save(argThat(values -> values.size() == 10));
        legacy.reserveStart(resumeId);
        legacy.releaseStart(resumeId);
        clearInvocations(agent);
        assertEquals(result, service.find(run.id()));
        verifyNoInteractions(agent);
    }

    @Test
    void temporaryPollingAndPersistenceErrorsRetryTheSameJobWithoutResubmitting() throws Exception {
        var cards = cards(3, false);
        var polls = new AtomicInteger();
        doAnswer(call -> {
            if (polls.getAndIncrement() == 0) throw new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM,
                    "GENERATION_AGENT_UNAVAILABLE", "Temporary failure");
            return done(call.getArgument(0));
        }).when(agent).find(any());
        doThrow(new IllegalStateException("DB failure")).doNothing().when(persistence).save(anyMap());
        var run = service.start(request(cards, false));
        executor.drain();
        assertEquals("done", service.find(run.id()).status());
        assertEquals(3, service.find(run.id()).evaluatedCount());
        verify(agent).submit(any(), anyString());
        verify(persistence, times(2)).save(anyMap());
    }

    @Test
    void cancelReturnsWhileHttpIsBlockedAndDiscardsItsLateResult() throws Exception {
        var cards = cards(10, false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return done(call.getArgument(0));
        }).when(agent).find(any());
        var run = service.start(request(cards, false));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("VACANCY_RELEVANCE_CANCELLED", service.cancel(run.id()).errorCode());
            assertEquals("failed", service.find(run.id()).status());
        } finally { release.countDown(); }
        executor.drain();
        verifyNoInteractions(persistence);
        legacy.reserveStart(resumeId);
        legacy.releaseStart(resumeId);
    }

    @Test
    void lostAgentJobFailsWithoutSavingAndAllowsAnotherRun() throws Exception {
        var cards = cards(1, false);
        doThrow(new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM,
                "VACANCY_RELEVANCE_JOB_LOST", "Job lost")).when(agent).find(any());
        var run = service.start(request(cards, false));
        executor.drain();
        assertEquals("VACANCY_RELEVANCE_JOB_LOST", service.find(run.id()).errorCode());
        verifyNoInteractions(persistence);
        legacy.reserveStart(resumeId);
        legacy.releaseStart(resumeId);
    }

    @Test
    void foreignResultFailsTheBatchWithoutWritingEvaluations() throws Exception {
        var cards = cards(1, false);
        doAnswer(call -> new GenerationAgentRelevanceClient.GenerationJob(call.getArgument(0), "done",
                new GenerationAgentRelevanceClient.GenerationResult(List.of(
                        new GenerationAgentRelevanceClient.Evaluation("foreign", "TARGET", "high", "Java"))), null))
                .when(agent).find(any());
        var run = service.start(request(cards, false));
        executor.drain();
        assertEquals("INVALID_GENERATION_AGENT_RESPONSE", service.find(run.id()).errorCode());
        verifyNoInteractions(persistence);
    }

    @Test
    void uncertainSubmissionIsNotAutomaticallySentAgain() throws Exception {
        var cards = cards(1, false);
        doThrow(new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM,
                "GENERATION_AGENT_UNAVAILABLE", "Submission failed")).when(agent).submit(any(), anyString());
        var run = service.start(request(cards, false));
        executor.drain();
        assertEquals("failed", service.find(run.id()).status());
        verify(agent).submit(any(), anyString());
        verify(agent, never()).find(any());
        verifyNoInteractions(persistence);
        legacy.reserveStart(resumeId);
        legacy.releaseStart(resumeId);
    }

    @Test
    void waitingForMemoryDoesNotConsumeTheExecutionDeadline() throws Exception {
        var cards = cards(1, false);
        var nanos = new AtomicLong();
        service = new ParallelVacancyRelevanceService(vacancies, resumes, agent, persistence, legacy,
                clock, executor, Duration.ofMillis(1), Duration.ofMinutes(10), nanos::get);
        var polls = new AtomicInteger();
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            nanos.addAndGet(Duration.ofMinutes(6).toNanos());
            return polls.incrementAndGet() <= 3
                    ? new GenerationAgentRelevanceClient.GenerationJob(id, "queued", null, null) : done(id);
        }).when(agent).find(any());
        var run = service.start(request(cards, false));
        executor.drain();
        assertEquals("done", service.find(run.id()).status());
        assertEquals(4, polls.get());
        verify(agent).submit(any(), anyString());
    }

    @Test
    void prolongedAgentUnavailabilityStillTimesOutWhileWaiting() throws Exception {
        var cards = cards(1, false);
        var nanos = new AtomicLong();
        service = new ParallelVacancyRelevanceService(vacancies, resumes, agent, persistence, legacy,
                clock, executor, Duration.ofMillis(1), Duration.ofMinutes(10), nanos::get);
        doAnswer(call -> {
            nanos.addAndGet(Duration.ofMinutes(11).toNanos());
            throw new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM,
                    "GENERATION_AGENT_UNAVAILABLE", "Unavailable");
        }).when(agent).find(any());
        var run = service.start(request(cards, false));
        executor.drain();
        assertEquals("VACANCY_RELEVANCE_TIMEOUT", service.find(run.id()).errorCode());
        verify(agent).submit(any(), anyString());
        verifyNoInteractions(persistence);
    }

    @Test
    void exhaustedPersistenceDeadlineIsNotClassifiedAsAModelError() throws Exception {
        var cards = cards(1, false);
        var nanos = new AtomicLong();
        service = new ParallelVacancyRelevanceService(vacancies, resumes, agent, persistence, legacy,
                clock, executor, Duration.ofMillis(1), Duration.ofMinutes(10), nanos::get);
        doAnswer(call -> {
            nanos.addAndGet(Duration.ofMinutes(11).toNanos());
            throw new IllegalStateException("Persistence failure");
        }).when(persistence).save(anyMap());
        var run = service.start(request(cards, false));
        executor.drain();
        assertEquals("VACANCY_RELEVANCE_PERSISTENCE_FAILED", service.find(run.id()).errorCode());
        verify(persistence).save(anyMap());
        verify(agent).submit(any(), anyString());
    }

    @Test
    void terminalHistoryExpiresTwentyFourHoursAfterCompletion() throws Exception {
        var cards = cards(1, false);
        var run = service.start(request(cards, false));
        executor.drain();
        clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertEquals("done", service.find(run.id()).status());
        clock.advanceSeconds(1);
        assertEquals("VACANCY_RELEVANCE_RUN_NOT_FOUND", assertThrows(ApplicationException.class,
                () -> service.find(run.id())).machineCode());
    }

    @Test
    void completedHistoryIsLimitedToOneHundredParents() throws Exception {
        var cards = cards(1, false);
        UUID first = null, last = null;
        for (int index = 0; index < 101; index++) {
            last = service.start(request(cards, true)).id();
            if (first == null) first = last;
            executor.drain();
            clock.advanceSeconds(1);
        }
        assertEquals("done", service.find(last).status());
        UUID oldest = first;
        assertEquals("VACANCY_RELEVANCE_RUN_NOT_FOUND", assertThrows(ApplicationException.class,
                () -> service.find(oldest)).machineCode());
    }

    @Test
    void progressRetainsTheOriginalEvaluatedBaseline() throws Exception {
        var cards = cards(15, false);
        var run = service.start(new ParallelVacancyRelevanceRequest(cards.stream().map(Vacancy::id).toList(),
                215, 200, false));
        executor.drain();
        var result = service.find(run.id());
        assertEquals(200, result.evaluatedBefore());
        assertEquals(215, result.totalCount());
        assertEquals(15, result.evaluatedCount());
        assertEquals(2, result.batchCount());
        assertEquals(215, service.notificationRuns().getFirst().evaluatedCount());
    }

    @Test
    void longVacancyDescriptionsReduceBatchSizeWithoutTruncation() {
        var first = input("1", "a".repeat(20_000));
        var second = input("2", "b".repeat(20_000));
        var oversized = input("3", "c".repeat(40_000));
        var batches = ParallelVacancyRelevanceService.split(List.of(first, second, oversized));
        assertEquals(List.of(List.of(first), List.of(second), List.of(oversized)), batches);
        assertEquals(40_000, batches.getLast().getFirst().plainText().length());
    }

    private GenerationAgentRelevanceClient.VacancyInput input(String hhId, String text) {
        return new GenerationAgentRelevanceClient.VacancyInput(hhId, "Java", "Company", "", null,
                "", "", List.of(), "", List.of(), "", null, null, List.of(), text);
    }

    private GenerationAgentRelevanceClient.GenerationJob done(UUID id) {
        var items = jobs.get(id).vacancies().stream().map(input ->
                new GenerationAgentRelevanceClient.Evaluation(input.hhId(), "TARGET", "high", "Java experience")).toList();
        return new GenerationAgentRelevanceClient.GenerationJob(id, "done",
                new GenerationAgentRelevanceClient.GenerationResult(items), null);
    }

    private ParallelVacancyRelevanceRequest request(List<Vacancy> cards, boolean benchmark) {
        return new ParallelVacancyRelevanceRequest(cards.stream().map(Vacancy::id).toList(), null, null, benchmark);
    }

    private List<Vacancy> cards(int count, boolean rated) {
        return cards(count, rated ? count : 0);
    }

    private List<Vacancy> cards(int count, int evaluated) {
        var relevance = new VacancyRelevance(VacancyRelevanceCategory.TARGET,
                VacancyRelevanceConfidence.high, "Existing evaluation", NOW);
        var cards = IntStream.range(0, count).mapToObj(index -> new Vacancy(UUID.randomUUID(), resumeId,
                Integer.toString(index), "Java", "company", "Company", null, "", "", "", null, "", List.of(),
                false, VacancyStatus.NEW, null, false, NOW, NOW, NOW, NOW, index < evaluated ? relevance : null)).toList();
        doAnswer(call -> {
            List<UUID> ids = call.getArgument(0);
            return cards.stream().filter(card -> ids.contains(card.id())).toList();
        }).when(vacancies).findAllByIds(anyList());
        return cards;
    }

    private static class TrackingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        private final List<Future<?>> tasks = new CopyOnWriteArrayList<>();
        @Override public void execute(Runnable command) { tasks.add(delegate.submit(command)); }
        void drain() throws Exception { for (var task : tasks) task.get(5, TimeUnit.SECONDS); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
