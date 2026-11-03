package org.hhclicker.backend.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.market.MarketAnalysisRepository.Run;
import org.hhclicker.backend.market.MarketAnalysisRepository.Source;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketAnalysisServiceTests {
    private final MarketAnalysisRepository repository = mock(MarketAnalysisRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final MarketGenerationClient agent = mock(MarketGenerationClient.class);
    private final MarketAnalysisCoordinator coordinator = mock(MarketAnalysisCoordinator.class);
    private final MarketAnalysisService service = new MarketAnalysisService(repository, resumes, agent, coordinator);

    @BeforeEach
    void reloadActiveSnapshotByDefault() {
        // Legacy-run fixture: no batch ownership; new-run registration is delegated explicitly.
        doAnswer(call -> { repository.insert(call.getArgument(0)); return null; })
                .when(coordinator).initialize(any());
        when(repository.find(any())).thenAnswer(invocation -> repository.active().stream()
                .filter(run -> run.id().equals(invocation.getArgument(0))).findFirst());
    }

    @Test
    void productionConstructorRegistersPersistentBatchesAndRoutesManagementToTheirCoordinator() {
        var parallel = mock(MarketAnalysisCoordinator.class);
        var production = new MarketAnalysisService(repository, resumes, agent, parallel);
        UUID resumeId = UUID.randomUUID();
        var run = MarketBatchFixtures.run(resumeId, 9);
        when(resumes.findById(resumeId)).thenReturn(Optional.of(new HhResume(resumeId, UUID.randomUUID(),
                "hh-1", "Java", null, Instant.now(), Instant.now(), "Java", Instant.now())));
        when(repository.sources(resumeId)).thenReturn(run.sources());
        var summary = production.start(resumeId);
        var registered = ArgumentCaptor.forClass(Run.class);
        verify(parallel).initialize(registered.capture());
        assertEquals(summary.id(), registered.getValue().id());
        assertEquals(9, registered.getValue().sources().size());
        verify(repository, never()).insert(any());
        when(parallel.owns(run.id())).thenReturn(true);
        when(parallel.stop(run.id())).thenReturn(run);
        when(parallel.cancel(run.id())).thenReturn(run);
        production.stop(run.id());
        production.cancel(run.id());
        verify(parallel).stop(run.id());
        verify(parallel).cancel(run.id());
        verifyNoInteractions(agent);
    }

    @Test
    void startPersistsSourcesWithoutSendingOrStoringTheResumeText() {
        UUID resumeId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "123", "Java-разработчик", "id:1", "Компания",
                "Требуется Java", Instant.now());
        when(resumes.findById(resumeId)).thenReturn(Optional.of(new HhResume(resumeId, UUID.randomUUID(),
                "hh-1", "Java", null, Instant.now(), Instant.now(), "Java и Spring", Instant.now())));
        when(repository.sources(resumeId)).thenReturn(List.of(source));

        var summary = service.start(resumeId);
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).insert(saved.capture());
        assertEquals("RUNNING", summary.status());
        assertEquals("EXTRACT", summary.phase());
        assertEquals("", saved.getValue().resumeText());
        assertEquals(List.of(source), saved.getValue().sources());
        verifyNoInteractions(agent);
    }

    @Test
    void noFullTextsCannotCreateAnEmptyAnalysis() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findById(resumeId)).thenReturn(Optional.of(new HhResume(resumeId, UUID.randomUUID(),
                "hh-1", "Java", null, Instant.now(), Instant.now(), "Java", Instant.now())));
        assertThrows(ApplicationException.class, () -> service.start(resumeId));
        verify(repository, never()).insert(any());
    }

    @Test
    void missingResumeTextDoesNotBlockVacancyTermExtraction() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findById(resumeId)).thenReturn(Optional.of(new HhResume(resumeId, UUID.randomUUID(),
                "hh-1", "Java", null, Instant.now(), Instant.now(), null, null)));
        when(repository.sources(resumeId)).thenReturn(List.of(new Source(UUID.randomUUID(), "123", "Java",
                "id:1", "Компания", "Требуется Java", Instant.now())));

        service.start(resumeId);

        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).insert(saved.capture());
        assertEquals("", saved.getValue().resumeText());
    }

    @Test
    void failedGenerationBecomesAnExplicitFailedRun() {
        UUID jobId = UUID.randomUUID();
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "Java", Instant.now(), "RUNNING",
                "EXTRACT", 0, jobId, List.of(new Source(UUID.randomUUID(), "123", "Java", "id:1",
                "Компания", "Java", Instant.now())), List.of(), List.of(), null, null);
        when(repository.active()).thenReturn(List.of(run));
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId, "failed", null,
                new MarketGenerationClient.AgentError("RATE_LIMIT", "Лимит исчерпан.")));

        service.advanceActive();

        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals("FAILED", saved.getValue().status());
        assertEquals(jobId, saved.getValue().jobId());
        assertTrue(saved.getValue().error().contains("Лимит"));
    }

    @Test
    void resumeKeepsCompletedBatchesAndRetriesOnlyTheUnfinishedBatch() {
        UUID runId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        List<Source> sources = IntStream.range(0, 9).mapToObj(index -> new Source(UUID.randomUUID(),
                Integer.toString(index), "Java", "id:1", "Компания", "Нужен Java", Instant.now())).toList();
        var fact = new MarketAnalysisRepository.Fact(sources.getFirst().id(), "Java", "TECHNOLOGY", "MENTIONED", "Java");
        var diagnostic = new MarketAnalysisRepository.Diagnostic("EXTRACT", sources.getFirst().id(),
                "FACT_SHAPE", "", "");
        Run failed = new Run(runId, resumeId, "Java", "", Instant.now(), "FAILED", "EXTRACT", 8,
                null, sources, List.of(fact), List.of(), List.of(diagnostic), null, "Ошибка авторизации");
        UUID newJobId = UUID.randomUUID();
        doReturn(Optional.of(failed)).when(repository).find(runId);
        when(agent.extract(any(), eq(runId + "-extract-8-retry-legacy")))
                .thenReturn(new MarketGenerationClient.SubmittedJob(newJobId, "queued"));

        var summary = service.resume(runId);

        var request = ArgumentCaptor.forClass(MarketGenerationClient.ExtractionRequest.class);
        verify(agent).extract(request.capture(), eq(runId + "-extract-8-retry-legacy"));
        assertEquals(List.of(sources.get(8).id()), request.getValue().vacancies().stream()
                .map(MarketGenerationClient.VacancyInput::id).toList());
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals(runId, saved.getValue().id());
        assertEquals("RUNNING", summary.status());
        assertEquals(8, saved.getValue().position());
        assertEquals(newJobId, saved.getValue().jobId());
        assertEquals(List.of(fact), saved.getValue().facts());
        assertEquals(List.of(diagnostic), saved.getValue().diagnostics());
        assertNull(saved.getValue().error());
        assertEquals(8, saved.getValue().estimateStartPosition());
        assertNotNull(saved.getValue().estimateStartedAt());
        assertNull(summary.estimatedRemainingSeconds());
        verify(repository, never()).sources(any());
    }

    @Test
    void resumeUsesAnewKeyAfterAnAgentJobFailed() {
        UUID runId = UUID.randomUUID();
        UUID oldJobId = UUID.randomUUID();
        UUID newJobId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "123", "Java", "id:1", "Компания", "Java", Instant.now());
        Run failed = new Run(runId, UUID.randomUUID(), "Java", "", Instant.now(), "FAILED", "EXTRACT", 0,
                oldJobId, List.of(source), List.of(), List.of(), null, "Ошибка авторизации");
        doReturn(Optional.of(failed)).when(repository).find(runId);
        when(agent.extraction(oldJobId)).thenReturn(new MarketGenerationClient.ExtractionJob(oldJobId,
                "failed", null, new MarketGenerationClient.AgentError("AUTH_CHECK_FAILED", "Ошибка авторизации")));
        when(agent.extract(any(), eq(runId + "-extract-0-retry-" + oldJobId)))
                .thenReturn(new MarketGenerationClient.SubmittedJob(newJobId, "queued"));

        service.resume(runId);

        verify(agent).extract(any(), eq(runId + "-extract-0-retry-" + oldJobId));
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals(newJobId, saved.getValue().jobId());
    }

    @Test
    void resumeSkipsAFailedSubmissionWhoseIdWasNotSaved() {
        UUID runId = UUID.randomUUID();
        UUID previousJobId = UUID.randomUUID();
        UUID newJobId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "123", "Java", "id:1", "Компания", "Java", Instant.now());
        Run failed = new Run(runId, UUID.randomUUID(), "Java", "", Instant.now(), "FAILED", "EXTRACT", 0,
                null, List.of(source), List.of(), List.of(), null, "Ошибка");
        doReturn(Optional.of(failed)).when(repository).find(runId);
        when(agent.extract(any(), eq(runId + "-extract-0-retry-legacy")))
                .thenReturn(new MarketGenerationClient.SubmittedJob(previousJobId, "failed"));
        when(agent.extract(any(), eq(runId + "-extract-0-retry-" + previousJobId)))
                .thenReturn(new MarketGenerationClient.SubmittedJob(newJobId, "queued"));

        service.resume(runId);

        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals(newJobId, saved.getValue().jobId());
        assertEquals("RUNNING", saved.getValue().status());
    }

    @Test
    void resumeReusesAnExistingJobIfItsResultWasNotReadYet() {
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "123", "Java", "id:1", "Компания", "Java", Instant.now());
        Run failed = new Run(runId, UUID.randomUUID(), "Java", "", Instant.now(), "FAILED", "EXTRACT", 0,
                jobId, List.of(source), List.of(), List.of(), null, "Ошибка обработки");
        doReturn(Optional.of(failed)).when(repository).find(runId);
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId,
                "done", new MarketGenerationClient.ExtractionResult(List.of(
                        new MarketGenerationClient.ExtractedVacancy(source.id(), List.of()))), null));

        service.resume(runId);

        verify(agent, never()).extract(any(), any());
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals(jobId, saved.getValue().jobId());
        assertEquals("RUNNING", saved.getValue().status());
    }

    @Test
    void resumeRejectsWhenAnotherAnalysisForTheResumeIsRunning() {
        UUID runId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "123", "Java", "id:1", "Компания", "Java", Instant.now());
        Run failed = new Run(runId, resumeId, "Java", "", Instant.now(), "FAILED", "EXTRACT", 0,
                null, List.of(source), List.of(), List.of(), null, "Ошибка");
        Run active = new Run(UUID.randomUUID(), resumeId, "Java", "", Instant.now(), "RUNNING", "EXTRACT", 0,
                null, List.of(source), List.of(), List.of(), null, null);
        doReturn(Optional.of(failed)).when(repository).find(runId);
        when(repository.hasOpen(resumeId, runId)).thenReturn(true);

        assertThrows(ApplicationException.class, () -> service.resume(runId));

        verify(repository, never()).save(any());
        verifyNoInteractions(agent);
    }

    @Test
    void resumeRejectsExpiredSnapshots() {
        UUID runId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "123", "Java", "id:1", "Компания", "Java", Instant.now());
        Run failed = new Run(runId, UUID.randomUUID(), "Java", "", Instant.now().minusSeconds(8 * 86400),
                "FAILED", "EXTRACT", 0, null, List.of(source), List.of(), List.of(), null, "Ошибка");
        doReturn(Optional.of(failed)).when(repository).find(runId);

        assertThrows(ApplicationException.class, () -> service.resume(runId));

        verify(repository, never()).save(any());
        verifyNoInteractions(agent);
    }

    @Test
    void latestVacancyLimitIsPreviewedAndStoredInTheSameOrder() {
        UUID resumeId = UUID.randomUUID();
        Instant first = Instant.parse("2026-09-01T00:00:00Z");
        List<Source> sources = IntStream.range(0, 5).mapToObj(index -> new Source(UUID.randomUUID(),
                Integer.toString(index), "Java", "id:1", "Компания", "Java", first.plusSeconds(index * 86400L))).toList();
        when(resumes.findById(resumeId)).thenReturn(Optional.of(new HhResume(resumeId, UUID.randomUUID(),
                "hh-1", "Java", null, Instant.now(), Instant.now(), null, null)));
        when(repository.sources(resumeId)).thenReturn(sources);

        var preview = service.preview(resumeId, 2);
        var summary = service.start(resumeId, 2);

        assertEquals(5, preview.availableCount());
        assertEquals(2, preview.selectedCount());
        assertEquals(sources.get(3).attemptedAt(), preview.earliestAttemptedAt());
        assertEquals(2, summary.vacancyCount());
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).insert(saved.capture());
        assertEquals(sources.subList(3, 5), saved.getValue().sources());
    }

    @Test
    void relevanceFilterAppliesBeforeLatestLimitInPreviewAndSnapshot() {
        UUID resumeId = UUID.randomUUID();
        UUID searchId = UUID.randomUUID();
        Instant first = Instant.parse("2026-09-01T00:00:00Z");
        List<Source> targetSources = IntStream.range(0, 3).mapToObj(index -> new Source(UUID.randomUUID(),
                Integer.toString(index), "Java", "id:1", "Компания", "Java", first.plusSeconds(index * 86400L))).toList();
        when(resumes.findById(resumeId)).thenReturn(Optional.of(new HhResume(resumeId, UUID.randomUUID(),
                "hh-1", "Java", null, Instant.now(), Instant.now(), null, null)));
        when(repository.searchName(resumeId, searchId)).thenReturn(Optional.of("Java"));
        when(repository.sources(resumeId, searchId, MarketRelevanceCategory.TARGET)).thenReturn(targetSources);

        var preview = service.preview(resumeId, 2, searchId, MarketRelevanceCategory.TARGET);
        service.start(resumeId, 2, searchId, MarketRelevanceCategory.TARGET);

        assertEquals(3, preview.availableCount());
        assertEquals(2, preview.selectedCount());
        assertEquals(targetSources.get(1).attemptedAt(), preview.earliestAttemptedAt());
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).insert(saved.capture());
        assertEquals(targetSources.subList(1, 3), saved.getValue().sources());
        verify(repository, times(2)).sources(resumeId, searchId, MarketRelevanceCategory.TARGET);
    }

    @Test
    void selectedSearchFiltersBothPreviewAndSnapshotAndKeepsItsName() {
        UUID resumeId = UUID.randomUUID();
        UUID searchId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "1", "Java", "id:1", "Компания", "Java", Instant.now());
        when(resumes.findById(resumeId)).thenReturn(Optional.of(new HhResume(resumeId, UUID.randomUUID(),
                "hh-1", "Java", null, Instant.now(), Instant.now(), null, null)));
        when(repository.searchName(resumeId, searchId)).thenReturn(Optional.of("Java remote"));
        when(repository.sources(resumeId, searchId)).thenReturn(List.of(source));

        assertEquals(1, service.preview(resumeId, null, searchId).selectedCount());
        var summary = service.start(resumeId, null, searchId);

        assertEquals("Java remote", summary.searchName());
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).insert(saved.capture());
        assertEquals(searchId, saved.getValue().searchId());
        assertEquals("Java remote", saved.getValue().searchName());
        assertEquals(List.of(source), saved.getValue().sources());
        verify(repository, never()).sources(resumeId);
    }

    @Test
    void stoppingWaitsForCurrentBatchAndResumeDoesNotSubmitUntilNextTick() {
        UUID jobId = UUID.randomUUID();
        List<Source> sources = IntStream.range(0, 9).mapToObj(index -> new Source(UUID.randomUUID(),
                Integer.toString(index), "Java", "id:1", "Компания", "Java", Instant.now())).toList();
        Run running = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(), "RUNNING",
                "EXTRACT", 0, jobId, sources, List.of(), List.of(), null, null);
        doReturn(Optional.of(running)).when(repository).find(running.id());

        assertEquals("STOPPING", service.stop(running.id()).status());
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        Run stopping = saved.getValue();
        when(repository.active()).thenReturn(List.of(stopping));
        doReturn(Optional.of(stopping)).when(repository).find(running.id());
        var items = sources.subList(0, 8).stream().map(source ->
                new MarketGenerationClient.ExtractedVacancy(source.id(), List.<MarketGenerationClient.ExtractedFact>of())).toList();
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId, "done",
                new MarketGenerationClient.ExtractionResult(items), null));

        service.advanceActive();

        verify(repository, times(2)).save(saved.capture());
        Run paused = saved.getValue();
        assertEquals("PAUSED", paused.status());
        assertEquals(8, paused.position());
        assertNull(paused.jobId());
        verify(agent, never()).extract(any(), any());
        doReturn(Optional.of(paused)).when(repository).find(running.id());
        assertEquals("RUNNING", service.resume(running.id()).status());
        verify(agent, never()).extract(any(), any());
    }

    @Test
    void stoppingBeforeSubmissionPausesImmediately() {
        Source source = new Source(UUID.randomUUID(), "1", "Java", "id:1", "Компания", "Java", Instant.now());
        Run running = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(), "RUNNING",
                "EXTRACT", 0, null, List.of(source), List.of(), List.of(), null, null);
        doReturn(Optional.of(running)).when(repository).find(running.id());

        assertEquals("PAUSED", service.stop(running.id()).status());

        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals(0, saved.getValue().position());
        verifyNoInteractions(agent);
    }

    @Test
    void cancellingAnInFlightAnalysisDiscardsTheJobAndCannotBeResumed() {
        UUID jobId = UUID.randomUUID();
        Source source = new Source(UUID.randomUUID(), "1", "Java", "id:1", "Компания", "Java", Instant.now());
        Run running = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(), "RUNNING",
                "EXTRACT", 0, jobId, List.of(source), List.of(), List.of(), null, null);
        doReturn(Optional.of(running)).when(repository).find(running.id());

        var cancelled = service.cancel(running.id());

        assertEquals("CANCELLED", cancelled.status());
        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertNull(saved.getValue().jobId());
        assertEquals(0, saved.getValue().position());
        verifyNoInteractions(agent);
        doReturn(Optional.of(saved.getValue())).when(repository).find(running.id());
        assertThrows(ApplicationException.class, () -> service.resume(running.id()));
    }

    @Test
    void cancellingAPausedAnalysisClosesItsSnapshot() {
        Run paused = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(), "PAUSED",
                "EXTRACT", 0, null, List.of(), List.of(), List.of(), null, null);
        doReturn(Optional.of(paused)).when(repository).find(paused.id());

        assertEquals("CANCELLED", service.cancel(paused.id()).status());
        verify(repository).save(argThat(run -> "CANCELLED".equals(run.status())));
    }

    @Test
    void extractionDiagnosticsAreAccumulatedWithoutFailingTheBatch() {
        UUID jobId = UUID.randomUUID();
        UUID vacancyId = UUID.randomUUID();
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "Java", Instant.now(), "RUNNING",
                "EXTRACT", 0, jobId, List.of(new Source(vacancyId, "123", "Java", "id:1",
                "Компания", "Требуется Kubernetes", Instant.now())), List.of(), List.of(), null, null);
        when(repository.active()).thenReturn(List.of(run));
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId, "done",
                new MarketGenerationClient.ExtractionResult(List.of(new MarketGenerationClient.ExtractedVacancy(
                        vacancyId, List.of(new MarketGenerationClient.ExtractedFact(
                                "Kubernetes", "TECHNOLOGY", "Kubernetes")))),
                        List.of(new MarketGenerationClient.AgentDiagnostic(vacancyId,
                                "EVIDENCE_NOT_IN_SOURCE", "Docker", "Docker"))), null));

        service.advanceActive();

        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals("RUNNING", saved.getValue().status());
        assertEquals(1, saved.getValue().position());
        assertEquals("Kubernetes", saved.getValue().facts().getFirst().name());
        assertEquals("EVIDENCE_NOT_IN_SOURCE", saved.getValue().diagnostics().getFirst().reason());
    }

    @Test
    void extractionAcceptsMoreThanFiftyExactTermsFromOneVacancy() {
        UUID jobId = UUID.randomUUID();
        UUID vacancyId = UUID.randomUUID();
        List<String> names = IntStream.range(0, 51).mapToObj(index -> "Term" + index).toList();
        Source source = new Source(vacancyId, "123", "Java", "id:1", "Компания",
                String.join(" ", names), Instant.now());
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "", Instant.now(),
                "RUNNING", "EXTRACT", 0, jobId, List.of(source), List.of(), List.of(), null, null);
        when(repository.active()).thenReturn(List.of(run));
        var facts = names.stream().map(name -> new MarketGenerationClient.ExtractedFact(
                name, "TECHNOLOGY", name)).toList();
        when(agent.extraction(jobId)).thenReturn(new MarketGenerationClient.ExtractionJob(jobId, "done",
                new MarketGenerationClient.ExtractionResult(List.of(
                        new MarketGenerationClient.ExtractedVacancy(vacancyId, facts))), null));

        service.advanceActive();

        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals(51, saved.getValue().facts().size());
        assertTrue(saved.getValue().diagnostics().isEmpty());
    }

    @Test
    void diagnosticRunStopsAfterExtractionWithoutCallingResumeCoverage() {
        UUID vacancyId = UUID.randomUUID();
        Source source = new Source(vacancyId, "123", "Java", "id:1", "Компания",
                "Требуется Kubernetes", Instant.now());
        Run run = new Run(UUID.randomUUID(), UUID.randomUUID(), "Java", "Java", Instant.now(),
                "RUNNING", "EXTRACT", 1, null, List.of(source),
                List.of(new MarketAnalysisRepository.Fact(vacancyId, "Kubernetes", "TECHNOLOGY", "MENTIONED", "Kubernetes")),
                List.of(), List.of(new MarketAnalysisRepository.Diagnostic(
                        "EXTRACT", vacancyId, "EVIDENCE_NOT_IN_SOURCE", "Docker", "Docker")), null, null);
        when(repository.active()).thenReturn(List.of(run));

        service.advanceActive();

        var saved = ArgumentCaptor.forClass(Run.class);
        verify(repository).save(saved.capture());
        assertEquals("COMPLETED", saved.getValue().status());
        assertTrue(saved.getValue().markdown().contains("## Частоты терминов"));
        assertFalse(saved.getValue().markdown().contains("EVIDENCE_NOT_IN_SOURCE"));
        verifyNoInteractions(agent);
    }

    @Test
    void existingReportUsesSavedFactsWithCurrentTableFormat() {
        UUID runId = UUID.randomUUID();
        UUID vacancyId = UUID.randomUUID();
        Source source = new Source(vacancyId, "123", "Java", "id:1", "Компания",
                "Оригинальный текст: нужен Kubernetes", Instant.now());
        Run run = new Run(runId, UUID.randomUUID(), "Java", "Java", Instant.now(),
                "COMPLETED", "DONE", 1, null, List.of(source),
                List.of(new MarketAnalysisRepository.Fact(vacancyId, "Kubernetes", "TECHNOLOGY", "MENTIONED", "Kubernetes")), List.of(),
                List.of(new MarketAnalysisRepository.Diagnostic(
                        "EXTRACT", vacancyId, "EVIDENCE_NOT_IN_SOURCE", "k8s", "Нужен k8s")),
                "Старый отчёт без источника", null);
        doReturn(Optional.of(run)).when(repository).find(runId);

        String markdown = service.markdown(runId);

        assertTrue(markdown.contains("| Kubernetes | 1 | 1 | 1.00 |"));
        assertFalse(markdown.contains("Старый отчёт без источника"));
        assertTrue(service.markdown(runId, true).contains("Оригинальный текст: нужен Kubernetes"));
        verifyNoInteractions(agent);
    }
}
