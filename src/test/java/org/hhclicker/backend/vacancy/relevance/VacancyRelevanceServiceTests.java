package org.hhclicker.backend.vacancy.relevance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.JsonNodeFactory;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.support.MutableClock;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VacancyRelevanceServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

    private final VacancyRepository vacancies = mock(VacancyRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final GenerationAgentRelevanceClient agent = mock(GenerationAgentRelevanceClient.class);
    private final VacancyRelevancePersistenceService persistence = mock(VacancyRelevancePersistenceService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private VacancyRelevanceService service;
    private final UUID resumeId = UUID.randomUUID();
    private final UUID firstId = UUID.randomUUID();
    private final UUID secondId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VacancyRelevanceService(vacancies, resumes, agent, persistence, clock);
        when(vacancies.findText(any())).thenReturn(Optional.empty());
    }

    @Test
    void startSendsOnlyUnassessedVacanciesAndUsesResumeText() {
        when(vacancies.findAllByIds(List.of(firstId, secondId))).thenReturn(List.of(
                enrichedVacancy(firstId, "101", resumeId),
                vacancy(secondId, "102", relevance(), resumeId)));
        resume("PHP-разработчик, Laravel");
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(jobId, "queued"));

        var response = service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)));

        assertEquals(jobId, response.id());
        assertEquals(1, response.requestedCount());
        var request = ArgumentCaptor.forClass(GenerationAgentRelevanceClient.GenerationRequest.class);
        verify(agent).submit(request.capture());
        assertEquals("Название резюме: PHP-разработчик\n\nPHP-разработчик, Laravel", request.getValue().resumeText());
        assertEquals(List.of("101"), request.getValue().vacancies().stream().map(GenerationAgentRelevanceClient.VacancyInput::hhId).toList());
        var input = request.getValue().vacancies().getFirst();
        assertEquals(List.of("REMOTE", "HYBRID"), input.workFormats());
        assertEquals("FULL", input.employmentForm());
        assertEquals(List.of("FIVE_ON_TWO_OFF"), input.workScheduleByDays());
        assertEquals("Москва, Пресненская набережная, 8", input.address());
        assertEquals(List.of("Программист, разработчик"), input.professionalRoles());
        assertEquals(Boolean.FALSE, input.internship());
        assertEquals(Boolean.TRUE, input.temporary());
        assertEquals(200_000L, input.normalizedSalary().from());
        assertEquals(250_000L, input.normalizedSalary().to());
        assertEquals("RUR", input.normalizedSalary().currency());
        assertEquals("", input.plainText());
    }

    @Test
    void startIncludesSavedTextOnlyWhereAvailableInMixedBatch() {
        when(vacancies.findAllByIds(List.of(firstId, secondId))).thenReturn(List.of(
                vacancy(firstId, "101", null, resumeId), vacancy(secondId, "102", null, resumeId)));
        when(vacancies.findText(firstId)).thenReturn(Optional.of(new VacancyText(
                "Разработка PHP-сервисов на Laravel.", Instant.now())));
        resume("PHP-разработчик");
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(jobId, "queued"));

        service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)));

        var request = ArgumentCaptor.forClass(GenerationAgentRelevanceClient.GenerationRequest.class);
        verify(agent).submit(request.capture());
        var byId = request.getValue().vacancies().stream().collect(
                java.util.stream.Collectors.toMap(GenerationAgentRelevanceClient.VacancyInput::hhId, input -> input));
        assertEquals("Разработка PHP-сервисов на Laravel.", byId.get("101").plainText());
        assertEquals("", byId.get("102").plainText());
    }

    @Test
    void partialResultIsPersistedAndTerminalPollIsIdempotent() {
        startedWithTwoVacancies();
        when(agent.find(jobId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(jobId, "done",
                new GenerationAgentRelevanceClient.GenerationResult(List.of(
                        new GenerationAgentRelevanceClient.Evaluation("101", "TARGET", "high", "Явная PHP-разработка."))), null));

        var first = service.find(jobId);
        var second = service.find(jobId);

        assertEquals("done", first.status());
        assertEquals(1, first.evaluatedCount());
        assertEquals(1, first.missingCount());
        assertSame(first, second);
        var saved = ArgumentCaptor.forClass(Map.class);
        verify(persistence).save(saved.capture());
        assertEquals(VacancyRelevanceCategory.TARGET, ((VacancyRelevance) saved.getValue().get(firstId)).category());
        verify(agent, times(1)).find(jobId);
    }

    @Test
    void foreignDuplicateOrInvalidAgentResultsFailRunWithoutWrites() {
        for (var items : List.of(
                List.of(new GenerationAgentRelevanceClient.Evaluation("foreign", "TARGET", "high", "x")),
                List.of(new GenerationAgentRelevanceClient.Evaluation("101", "TARGET", "high", "x"),
                        new GenerationAgentRelevanceClient.Evaluation("101", "NOISE", "low", "y")),
                List.of(new GenerationAgentRelevanceClient.Evaluation("101", "MAYBE", "high", "x")),
                List.of(new GenerationAgentRelevanceClient.Evaluation("101", "TARGET", "high", " ")))) {
            reset(vacancies, resumes, agent, persistence);
            service = new VacancyRelevanceService(vacancies, resumes, agent, persistence, clock);
            startedWithTwoVacancies();
            when(agent.find(jobId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(jobId, "done",
                    new GenerationAgentRelevanceClient.GenerationResult(items), null));

            var response = service.find(jobId);

            assertEquals("failed", response.status());
            assertEquals("INVALID_GENERATION_AGENT_RESPONSE", response.errorCode());
            assertSame(response, service.find(jobId));
            verifyNoInteractions(persistence);
            verify(agent, times(1)).find(jobId);
        }
    }

    @Test
    void malformedAgentResponseFailsRunAndAllowsNewEvaluation() {
        startedWithTwoVacancies();
        when(agent.find(jobId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(
                UUID.randomUUID(), "running", null, null));

        var response = service.find(jobId);

        assertEquals("failed", response.status());
        assertEquals("INVALID_GENERATION_AGENT_RESPONSE", response.errorCode());
        assertSame(response, service.find(jobId));
        verify(agent, times(1)).find(jobId);
        verifyNoInteractions(persistence);

        UUID nextJobId = UUID.randomUUID();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(nextJobId, "queued"));
        assertEquals(nextJobId, service.start(new VacancyRelevanceRequest(List.of(firstId, secondId))).id());
    }

    @Test
    void lostJobFailsRunCachesResponseAndAllowsNewEvaluation() {
        startedWithTwoVacancies();
        var lost = new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM, "VACANCY_RELEVANCE_JOB_LOST",
                "Задание оценки вакансий больше не найдено. Запустите оценку повторно.");
        when(agent.find(jobId)).thenThrow(lost);

        var response = service.find(jobId);

        assertEquals("failed", response.status());
        assertEquals("VACANCY_RELEVANCE_JOB_LOST", response.errorCode());
        assertEquals(lost.safeMessage(), response.errorMessage());
        assertEquals(0, response.evaluatedCount());
        assertSame(response, service.find(jobId));
        verify(agent, times(1)).find(jobId);
        verifyNoInteractions(persistence);
        var notification = service.notificationRuns().getFirst();
        assertEquals("failed", notification.status());
        assertNotNull(notification.revision());
        assertEquals(lost.safeMessage(), notification.errorMessage());

        UUID nextJobId = UUID.randomUUID();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(nextJobId, "queued"));
        assertEquals(nextJobId, service.start(new VacancyRelevanceRequest(List.of(firstId, secondId))).id());
    }

    @Test
    void temporaryAgentFailureKeepsRunActiveUntilSuccessfulPoll() {
        startedWithTwoVacancies();
        var unavailable = new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM,
                "GENERATION_AGENT_UNAVAILABLE", "Не удалось связаться с Generation Agent.");
        when(agent.find(jobId)).thenThrow(unavailable).thenReturn(
                new GenerationAgentRelevanceClient.GenerationJob(jobId, "done",
                        new GenerationAgentRelevanceClient.GenerationResult(List.of(
                                new GenerationAgentRelevanceClient.Evaluation("101", "TARGET", "high", "PHP"))), null));

        var exception = assertThrows(ApplicationException.class, () -> service.find(jobId));
        assertEquals("GENERATION_AGENT_UNAVAILABLE", exception.machineCode());
        var completed = service.find(jobId);

        assertEquals("done", completed.status());
        assertEquals(1, completed.evaluatedCount());
        verify(agent, times(2)).find(jobId);
        verify(persistence).save(any());
    }

    @Test
    void persistenceFailureIsNotClassifiedAsAgentContractFailure() {
        startedWithTwoVacancies();
        when(agent.find(jobId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(jobId, "done",
                new GenerationAgentRelevanceClient.GenerationResult(List.of(
                        new GenerationAgentRelevanceClient.Evaluation("101", "TARGET", "high", "PHP"))), null));
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).doNothing().when(persistence).save(any());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.find(jobId)));
        var completed = service.find(jobId);

        assertEquals("done", completed.status());
        verify(agent, times(2)).find(jobId);
        verify(persistence, times(2)).save(any());
    }

    @Test
    void failedGenerationIsReturnedSafelyAndCached() {
        startedWithTwoVacancies();
        when(agent.find(jobId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(jobId, "failed", null,
                new GenerationAgentRelevanceClient.GenerationError("RATE_LIMIT", "Лимит исчерпан.")));

        var response = service.find(jobId);

        assertEquals("failed", response.status());
        assertEquals("RATE_LIMIT", response.errorCode());
        assertEquals("Лимит исчерпан.", response.errorMessage());
        assertSame(response, service.find(jobId));
        verifyNoInteractions(persistence);
        verify(agent, times(1)).find(jobId);
    }

    @Test
    void activeRunCanBeRestoredAndSecondRunForTheResumeIsRejected() {
        startedWithTwoVacancies();
        when(agent.find(jobId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(
                jobId, "running", null, null));

        var active = service.active(resumeId);

        assertNotNull(active);
        assertEquals(jobId, active.id());
        assertEquals(resumeId, active.resumeId());
        when(vacancies.findAllByIds(List.of(firstId))).thenReturn(List.of(vacancy(firstId, "101", null, resumeId)));
        var duplicate = assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId))));
        assertEquals("VACANCY_RELEVANCE_ALREADY_RUNNING", duplicate.machineCode());
        verify(agent, times(1)).submit(any());
    }

    @Test
    void notificationSnapshotAdvancesTheJobAndExposesItsTerminalCounts() {
        when(vacancies.findAllByIds(List.of(firstId, secondId))).thenReturn(List.of(
                vacancy(firstId, "101", null, resumeId), vacancy(secondId, "102", null, resumeId)));
        resume("PHP-разработчик");
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(jobId, "queued"));
        service.start(new VacancyRelevanceRequest(List.of(firstId, secondId), true, 344, 300));
        when(agent.find(jobId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(jobId, "done",
                new GenerationAgentRelevanceClient.GenerationResult(List.of(
                        new GenerationAgentRelevanceClient.Evaluation("101", "TARGET", "high", "Явная PHP-разработка."))), null));

        var run = service.notificationRuns().getFirst();

        assertEquals(jobId, run.id());
        assertEquals(resumeId, run.resumeId());
        assertEquals("PHP-разработчик", run.resumeName());
        assertEquals("done", run.status());
        assertTrue(run.finalBatch());
        assertEquals(344, run.requestedCount());
        assertEquals(301, run.evaluatedCount());
        assertEquals(43, run.missingCount());
        verify(persistence).save(any());
    }

    @Test
    void startRejectsDuplicateMissingMixedResumeAndAbsentResumeText() {
        when(vacancies.findAllByIds(List.of(firstId))).thenReturn(List.of(vacancy(firstId, "101", null, resumeId)));
        var duplicate = assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId, firstId))));
        assertEquals("VACANCY_RELEVANCE_DUPLICATE_IDS", duplicate.machineCode());

        when(vacancies.findAllByIds(List.of(firstId, secondId))).thenReturn(List.of(vacancy(firstId, "101", null, resumeId)));
        assertEquals("VACANCY_RELEVANCE_VACANCY_NOT_FOUND", assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)))).machineCode());

        when(vacancies.findAllByIds(List.of(firstId, secondId))).thenReturn(List.of(
                vacancy(firstId, "101", null, resumeId), vacancy(secondId, "102", null, UUID.randomUUID())));
        assertEquals("VACANCY_RELEVANCE_MIXED_RESUMES", assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)))).machineCode());

        when(vacancies.findAllByIds(List.of(firstId))).thenReturn(List.of(vacancy(firstId, "101", null, resumeId)));
        resume(" ");
        assertEquals("RESUME_TEXT_REQUIRED", assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId)))).machineCode());
        verifyNoInteractions(agent);
    }

    @Test
    void unknownRunAndAlreadyAssessedSelectionAreRejected() {
        assertEquals("VACANCY_RELEVANCE_RUN_NOT_FOUND",
                assertThrows(ApplicationException.class, () -> service.find(UUID.randomUUID())).machineCode());
        when(vacancies.findAllByIds(List.of(firstId))).thenReturn(List.of(vacancy(firstId, "101", relevance(), resumeId)));
        assertEquals("VACANCY_RELEVANCE_ALREADY_DONE", assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId)))).machineCode());
    }

    @Test
    void terminalRunIsCachedUntilTwentyFourHoursAfterCompletionAndThenRemoved() {
        stubTwoVacanciesAndResume();
        UUID completedId = completeNextRun();
        var terminal = service.find(completedId);

        clock.advance(Duration.ofHours(24).minusNanos(1));
        assertSame(terminal, service.find(completedId));

        clock.advance(Duration.ofNanos(1));
        assertTrue(service.notificationRuns().isEmpty());
        assertEquals("VACANCY_RELEVANCE_RUN_NOT_FOUND",
                assertThrows(ApplicationException.class, () -> service.find(completedId)).machineCode());
        verify(agent, times(1)).find(completedId);
    }

    @Test
    void longRunningRunReceivesFullRetentionAfterCompletion() {
        startedWithTwoVacancies();
        clock.advance(Duration.ofDays(3));
        when(agent.find(jobId)).thenReturn(doneJob(jobId));

        var terminal = service.find(jobId);
        clock.advance(Duration.ofHours(24).minusNanos(1));
        assertSame(terminal, service.find(jobId));

        clock.advance(Duration.ofNanos(1));
        assertEquals("VACANCY_RELEVANCE_RUN_NOT_FOUND",
                assertThrows(ApplicationException.class, () -> service.find(jobId)).machineCode());
        verify(agent, times(1)).find(jobId);
    }

    @Test
    void notificationHistoryKeepsOnlyHundredMostRecentlyCompletedRuns() {
        stubTwoVacanciesAndResume();
        var completedIds = new java.util.ArrayList<UUID>();
        for (int index = 0; index < 101; index++) {
            clock.advanceSeconds(1);
            completedIds.add(completeNextRun());
        }

        var history = service.notificationRuns();

        assertEquals(100, history.size());
        assertFalse(history.stream().anyMatch(run -> run.id().equals(completedIds.getFirst())));
        assertTrue(history.stream().anyMatch(run -> run.id().equals(completedIds.getLast())));
        assertEquals(completedIds.get(1), history.getFirst().id());
        assertEquals(completedIds.getLast(), history.getLast().id());
        assertEquals("VACANCY_RELEVANCE_RUN_NOT_FOUND",
                assertThrows(ApplicationException.class, () -> service.find(completedIds.getFirst())).machineCode());
        assertEquals("done", service.find(completedIds.getLast()).status());
        verify(agent, times(1)).find(completedIds.getLast());
    }

    @Test
    void activeRunIsNeitherCountedInHistoryLimitNorExpired() {
        stubTwoVacanciesAndResume();
        for (int index = 0; index < 100; index++) {
            clock.advanceSeconds(1);
            completeNextRun();
        }
        UUID activeId = UUID.randomUUID();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(activeId, "queued"));
        service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)));
        when(agent.find(activeId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(
                activeId, "running", null, null));

        var fullHistory = service.notificationRuns();
        assertEquals(101, fullHistory.size());
        assertTrue(fullHistory.stream().anyMatch(run -> run.id().equals(activeId) && "running".equals(run.status())));

        clock.advance(Duration.ofDays(2));
        var agedHistory = service.notificationRuns();
        assertEquals(List.of(activeId), agedHistory.stream().map(VacancyRelevanceNotificationRun::id).toList());
        assertEquals("running", agedHistory.getFirst().status());
        verify(agent, times(2)).find(activeId);
    }

    @Test
    void startRemovesExpiredTerminalRunsBeforeCreatingNewRun() {
        stubTwoVacanciesAndResume();
        UUID completedId = completeNextRun();
        clock.advance(Duration.ofHours(24));
        UUID nextId = UUID.randomUUID();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(nextId, "queued"));
        when(agent.find(nextId)).thenReturn(new GenerationAgentRelevanceClient.GenerationJob(
                nextId, "running", null, null));

        service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)));
        var history = service.notificationRuns();

        assertEquals(List.of(nextId), history.stream().map(VacancyRelevanceNotificationRun::id).toList());
        assertFalse(history.stream().anyMatch(run -> run.id().equals(completedId)));
    }

    @Test
    void oldActiveRunStillRejectsSecondRunForTheSameResume() {
        startedWithTwoVacancies();
        clock.advance(Duration.ofDays(2));

        var duplicate = assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId, secondId))));

        assertEquals("VACANCY_RELEVANCE_ALREADY_RUNNING", duplicate.machineCode());
        verify(agent, times(1)).submit(any());
    }

    @Test
    void blockedSubmitRejectsSameResumeWithoutBlockingAnotherResume() throws Exception {
        stubTwoVacanciesAndResume();
        UUID otherResumeId = UUID.randomUUID();
        UUID otherVacancyId = UUID.randomUUID();
        when(vacancies.findAllByIds(List.of(otherVacancyId))).thenReturn(List.of(
                vacancy(otherVacancyId, "101", null, otherResumeId)));
        resume(otherResumeId, "Java-разработчик");
        UUID firstJobId = UUID.randomUUID();
        UUID otherJobId = UUID.randomUUID();
        var submitStarted = new CountDownLatch(1);
        var releaseSubmit = new CountDownLatch(1);
        var firstSubmission = new AtomicBoolean(true);
        when(agent.submit(any())).thenAnswer(invocation -> {
            if (firstSubmission.compareAndSet(true, false)) {
                submitStarted.countDown();
                if (!releaseSubmit.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Первый submit не был освобождён");
                }
                return new GenerationAgentRelevanceClient.SubmittedJob(firstJobId, "queued");
            }
            return new GenerationAgentRelevanceClient.SubmittedJob(otherJobId, "queued");
        });
        ExecutorService executor = Executors.newFixedThreadPool(3);
        var first = executor.submit(() -> service.start(new VacancyRelevanceRequest(List.of(firstId, secondId))));
        try {
            assertTrue(submitStarted.await(10, TimeUnit.SECONDS), "Первый submit не начался");

            var duplicate = executor.submit(() -> service.start(new VacancyRelevanceRequest(List.of(firstId, secondId))));
            var duplicateFailure = assertThrows(ExecutionException.class,
                    () -> duplicate.get(10, TimeUnit.SECONDS));
            var conflict = assertInstanceOf(ApplicationException.class, duplicateFailure.getCause());
            assertEquals("VACANCY_RELEVANCE_ALREADY_RUNNING", conflict.machineCode());

            var other = executor.submit(() -> service.start(new VacancyRelevanceRequest(List.of(otherVacancyId))));
            assertEquals(otherJobId, other.get(10, TimeUnit.SECONDS).id());

            releaseSubmit.countDown();
            assertEquals(firstJobId, first.get(10, TimeUnit.SECONDS).id());
            verify(agent, times(2)).submit(any());
        } finally {
            releaseSubmit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedSubmitReleasesResumeReservation() {
        stubTwoVacanciesAndResume();
        var failure = new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM,
                "GENERATION_AGENT_UNAVAILABLE", "Не удалось связаться с Generation Agent.");
        when(agent.submit(any())).thenThrow(failure);

        assertSame(failure, assertThrows(ApplicationException.class,
                () -> service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)))));

        UUID nextJobId = UUID.randomUUID();
        doReturn(new GenerationAgentRelevanceClient.SubmittedJob(nextJobId, "queued"))
                .when(agent).submit(any());
        assertEquals(nextJobId, service.start(new VacancyRelevanceRequest(List.of(firstId, secondId))).id());
        verify(agent, times(2)).submit(any());
    }

    @Test
    void historyEvictionUsesCompletionOrderInsteadOfCreationOrder() {
        stubTwoVacanciesAndResume();
        UUID otherResumeId = UUID.randomUUID();
        UUID otherVacancyId = UUID.randomUUID();
        when(vacancies.findAllByIds(List.of(otherVacancyId))).thenReturn(List.of(
                vacancy(otherVacancyId, "101", null, otherResumeId)));
        resume(otherResumeId, "Java-разработчик");
        UUID createdFirstCompletedLast = UUID.randomUUID();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(
                createdFirstCompletedLast, "queued"));
        service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)));

        clock.advanceSeconds(1);
        UUID createdLaterCompletedFirst = UUID.randomUUID();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(
                createdLaterCompletedFirst, "queued"));
        service.start(new VacancyRelevanceRequest(List.of(otherVacancyId)));
        clock.advanceSeconds(1);
        when(agent.find(createdLaterCompletedFirst)).thenReturn(doneJob(createdLaterCompletedFirst));
        service.find(createdLaterCompletedFirst);

        for (int index = 0; index < 99; index++) {
            clock.advanceSeconds(1);
            completeNextRun(otherVacancyId);
        }
        clock.advanceSeconds(1);
        when(agent.find(createdFirstCompletedLast)).thenReturn(doneJob(createdFirstCompletedLast));
        service.find(createdFirstCompletedLast);

        var history = service.notificationRuns();

        assertEquals(100, history.size());
        assertTrue(history.stream().anyMatch(run -> run.id().equals(createdFirstCompletedLast)));
        assertFalse(history.stream().anyMatch(run -> run.id().equals(createdLaterCompletedFirst)));
        assertEquals("done", service.find(createdFirstCompletedLast).status());
        assertEquals("VACANCY_RELEVANCE_RUN_NOT_FOUND",
                assertThrows(ApplicationException.class, () -> service.find(createdLaterCompletedFirst)).machineCode());
        verify(agent, times(1)).find(createdFirstCompletedLast);
        verify(agent, times(1)).find(createdLaterCompletedFirst);
    }

    private void startedWithTwoVacancies() {
        stubTwoVacanciesAndResume();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(jobId, "queued"));
        service.start(new VacancyRelevanceRequest(List.of(firstId, secondId)));
    }

    private void stubTwoVacanciesAndResume() {
        when(vacancies.findText(any())).thenReturn(Optional.empty());
        when(vacancies.findAllByIds(List.of(firstId, secondId))).thenReturn(List.of(
                vacancy(firstId, "101", null, resumeId), vacancy(secondId, "102", null, resumeId)));
        resume("PHP-разработчик");
    }

    private UUID completeNextRun() {
        return completeNextRun(new VacancyRelevanceRequest(List.of(firstId, secondId)));
    }

    private UUID completeNextRun(UUID vacancyId) {
        return completeNextRun(new VacancyRelevanceRequest(List.of(vacancyId)));
    }

    private UUID completeNextRun(VacancyRelevanceRequest request) {
        UUID id = UUID.randomUUID();
        when(agent.submit(any())).thenReturn(new GenerationAgentRelevanceClient.SubmittedJob(id, "queued"));
        service.start(request);
        when(agent.find(id)).thenReturn(doneJob(id));
        service.find(id);
        return id;
    }

    private GenerationAgentRelevanceClient.GenerationJob doneJob(UUID id) {
        return new GenerationAgentRelevanceClient.GenerationJob(id, "done",
                new GenerationAgentRelevanceClient.GenerationResult(List.of(
                        new GenerationAgentRelevanceClient.Evaluation("101", "TARGET", "high", "PHP"))), null);
    }

    private void resume(String plainText) {
        resume(resumeId, plainText);
    }

    private void resume(UUID id, String plainText) {
        HhResume resume = mock(HhResume.class);
        when(resume.name()).thenReturn("PHP-разработчик");
        when(resume.accountId()).thenReturn(UUID.randomUUID());
        when(resume.plainText()).thenReturn(plainText);
        when(resumes.findById(id)).thenReturn(Optional.of(resume));
    }

    private Vacancy vacancy(UUID id, String hhId, VacancyRelevance relevance, UUID owner) {
        Instant now = Instant.parse("2026-09-11T10:00:00Z");
        return new Vacancy(id, owner, hhId, "PHP-разработчик", "1", "Компания", null,
                "Москва", "Удалённо", "3–6 лет", null, "250 000 ₽", List.of(), false,
                VacancyStatus.NEW, null, false, now, now, now, now, relevance);
    }

    private Vacancy enrichedVacancy(UUID id, String hhId, UUID owner) {
        Vacancy source = vacancy(id, hhId, null, owner);
        var enrichment = JsonNodeFactory.instance.objectNode();
        enrichment.putArray("workFormats").add("REMOTE").add("HYBRID");
        enrichment.put("employmentForm", "FULL");
        enrichment.putArray("workScheduleByDays").add("FIVE_ON_TWO_OFF");
        enrichment.put("address", "Москва, Пресненская набережная, 8");
        enrichment.putArray("professionalRoles").add("Программист, разработчик");
        enrichment.put("internship", false);
        enrichment.put("temporary", true);
        return new Vacancy(source.id(), source.resumeId(), source.hhVacancyId(), source.title(), source.employerId(),
                source.companyName(), new VacancySalary(200_000L, 250_000L, SalaryCurrency.RUR, false),
                source.location(), source.workFormat(), source.experience(), source.publishedAt(), source.salaryText(),
                source.labels(), source.hasQuestionnaire(), source.status(), source.banScope(), source.queuedForApplication(),
                source.firstSeenAt(), source.lastSeenAt(), source.createdAt(), source.updatedAt(), source.relevance(), enrichment);
    }

    private VacancyRelevance relevance() {
        return new VacancyRelevance(VacancyRelevanceCategory.TARGET, VacancyRelevanceConfidence.high,
                "Явная PHP-разработка.", Instant.parse("2026-09-11T10:00:00Z"));
    }
}
