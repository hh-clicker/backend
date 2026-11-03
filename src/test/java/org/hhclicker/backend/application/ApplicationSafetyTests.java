package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.application.repository.ApplicationRepository;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.service.BrowserProfileLookup;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyApplicationSource;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.catalog.service.VacancyCatalogService;
import org.hhclicker.backend.vacancy.selection.service.VacancySelectionService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationSafetyTests {
    private final ApplicationRepository repository = mock(ApplicationRepository.class);
    private final VacancyCatalogService vacancies = mock(VacancyCatalogService.class);
    private final ResumeService resumes = mock(ResumeService.class);
    private final BrowserProfileLookup profiles = mock(BrowserProfileLookup.class);
    private final VacancySelectionService vacancySelection = mock(VacancySelectionService.class);
    private final ApplicationService service = new ApplicationService(repository, resumes, profiles, vacancies, vacancySelection);
    private final UUID runId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID vacancyId = UUID.randomUUID();

    @Test
    void descriptionIsBoundToTaskAndSavedEvenWithoutSubmission() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        service.complete(itemId, Outcome.QUESTIONNAIRE, null, "Tasks\n- Build services");
        verify(vacancies).saveText(vacancyId, "Tasks\n- Build services");
        verify(repository).finish(itemId, Outcome.QUESTIONNAIRE, false);
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void emptyDescriptionIsSavedButMissingSnapshotDoesNotOverwrite() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        service.complete(itemId, Outcome.NOT_SENT, null, "");
        verify(vacancies).saveText(vacancyId, "");
        clearInvocations(vacancies);
        service.complete(itemId, Outcome.NOT_SENT, null, null);
        verify(vacancies, never()).saveText(any(), any());
    }

    @Test
    void repeatedCompletedResultDoesNotOverwriteText() {
        prepare("SUCCEEDED", Instant.now(), RunStatus.COMPLETED, Outcome.SENT);
        service.complete(itemId, Outcome.SENT, null, "stale description");
        verify(vacancies, never()).saveText(any(), any());
    }

    @Test
    void lostResultAfterPermitKeepsDuplicateGuardAndPauses() {
        prepare("IN_PROGRESS", Instant.now(), RunStatus.RUNNING, null);
        service.lost(itemId);
        verify(repository).finish(itemId, Outcome.UNKNOWN, true);
        verify(repository).status(runId, RunStatus.PAUSED_ERROR);
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void failureBeforePermitDoesNotClaimSubmission() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        service.lost(itemId);
        verify(repository).finish(itemId, Outcome.NOT_SENT, false);
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void confirmedResponseWithUnconfirmedLetterIsNeverReapplied() {
        prepare("IN_PROGRESS", Instant.now(), RunStatus.RUNNING, null);
        service.complete(itemId, Outcome.SENT_LETTER_UNCONFIRMED);
        verify(repository).finish(itemId, Outcome.SENT_LETTER_UNCONFIRMED, true);
        verify(vacancies).markApplied(vacancyId);
        verify(repository, never()).status(any(), any());
    }

    @Test
    void completedItemIsIdempotent() {
        prepare("SUCCEEDED", Instant.now(), RunStatus.COMPLETED, Outcome.SENT);
        service.complete(itemId, Outcome.UNKNOWN);
        verify(repository, never()).finish(any(), any(), anyBoolean());
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void secondPermitIsDeniedEvenWhenFirstResponseWasLost() {
        prepare("IN_PROGRESS", Instant.now(), RunStatus.RUNNING, null);
        assertFalse(service.permit(itemId));
        verify(repository, never()).permit(any());
    }

    @Test
    void pausedRunCannotAuthorizeSubmission() {
        prepare("IN_PROGRESS", null, RunStatus.PAUSED_USER, null);
        assertFalse(service.permit(itemId));
        verify(repository, never()).permit(any());
    }

    @Test
    void successfulLateResultDoesNotRestartCancelledRun() {
        prepare("IN_PROGRESS", Instant.now(), RunStatus.CANCELLED, null);
        service.complete(itemId, Outcome.SENT);
        verify(repository).finish(itemId, Outcome.SENT, true);
        verify(vacancies).markApplied(vacancyId);
        verify(repository, never()).status(any(), any());
    }

    @Test
    void manualAcknowledgmentCannotClearGuardOrCreateSuccess() {
        prepare("ATTENTION_REQUIRED", Instant.now(), RunStatus.PAUSED_ERROR, Outcome.UNKNOWN);
        service.attention(itemId, "RESOLVED");
        verify(repository).acknowledge(itemId, "RESOLVED");
        verify(repository).finishIfIdle(runId);
        verify(repository, never()).advance(any(), anyInt());
        verify(repository, never()).finish(any(), any(), anyBoolean());
        verify(repository, never()).claim(any());
        verify(repository, never()).releaseUncertainGuard(any(), anyString());
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void repeatedStartReturnsAcceptedSnapshotWithoutCreatingNewRun() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        var existing = repository.find(runId).orElseThrow();
        var request = new Start(runId, existing.resumeId(), List.of(vacancyId), 1, 10, 10, "", true);
        assertSame(existing, service.start(request));
        verify(repository, never()).create(any(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void skippedQuestionnaireDoesNotPauseTheRestOfTheQueue() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        service.complete(itemId, Outcome.QUESTIONNAIRE);
        verify(repository).finish(itemId, Outcome.QUESTIONNAIRE, false);
        verify(vacancies).markQuestionnaire(vacancyId);
        verify(repository, never()).status(any(), any());
    }

    @Test
    void newExplicitRunAcceptsQueuedVacancyWithUnconfirmedHistory() {
        prepare("QUEUED", null, RunStatus.RUNNING, null);
        var run = repository.find(runId).orElseThrow();
        when(repository.find(runId)).thenReturn(Optional.empty(), Optional.of(run));
        var now = Instant.now();
        when(resumes.findById(run.resumeId())).thenReturn(new ResumeProfile(
                new HhResume(run.resumeId(), run.accountId(), run.hhResumeId(), run.resumeName(),
                        ResumeAutomationSettings.defaults(), now, now), "Account", null));
        var profile = mock(BrowserProfile.class);
        when(profile.id()).thenReturn(run.profileId());
        when(profiles.forAccount(run.accountId())).thenReturn(profile);
        var vacancy = vacancies.findById(vacancyId).vacancy();
        when(vacancy.resumeId()).thenReturn(run.resumeId());
        when(vacancy.hhVacancyId()).thenReturn("123");
        when(vacancy.title()).thenReturn("Developer");
        when(vacancy.companyName()).thenReturn("Example");
        when(vacancies.findById(vacancyId)).thenReturn(new VacancyListing(vacancy, UUID.randomUUID(), true));
        when(vacancies.applicationSource(vacancyId)).thenReturn(new VacancyApplicationSource("https://hh.ru/search/vacancy", 0));
        assertSame(run, service.start(new Start(runId, run.resumeId(), List.of(vacancyId), 1, 10, 10, "", true)));
        verify(repository).add(eq(runId), eq(run.accountId()), eq(vacancyId), eq("123"),
                eq("Developer"), eq("Example"), anyString(), eq(0), eq(0), eq(false));
        // Starting alone does not remove protection; only a safely claimed new task does.
        verify(repository, never()).releaseUncertainGuard(any(), anyString());
    }

    @Test
    void claimTransfersOnlyEligibleUncertainProtectionUnderLock() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        var run = repository.find(runId).orElseThrow();
        var task = new Task(itemId, run.profileId(), run.hhResumeId(), "123", "https://hh.ru/search/vacancy", 0, "");
        when(repository.next(run.profileId())).thenReturn(Optional.of(task));
        assertEquals(Optional.of(task), service.claim(run.profileId()));
        var order = inOrder(repository);
        order.verify(repository).lock();
        order.verify(repository).claim(itemId);
        order.verify(repository).guarded(run.accountId(), "123", itemId);
        order.verify(repository).releaseUncertainGuard(run.accountId(), "123");
        verify(repository, never()).permit(any());
    }

    @Test
    void confirmedOrLiveGuardStillBlocksClaimAndPermit() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        var run = repository.find(runId).orElseThrow();
        when(repository.next(run.profileId())).thenReturn(Optional.of(
                new Task(itemId, run.profileId(), run.hhResumeId(), "123", "https://hh.ru/search/vacancy", 0, "")));
        when(repository.guarded(run.accountId(), "123", itemId)).thenReturn(true);
        assertTrue(service.claim(run.profileId()).isEmpty());
        assertFalse(service.permit(itemId));
        verify(repository, never()).releaseUncertainGuard(any(), anyString());
        verify(repository, never()).permit(any());
    }

    @Test
    void confirmedRecheckWithoutSubmissionResolvesOldAttention() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        var run = repository.find(runId).orElseThrow();
        service.complete(itemId, Outcome.ALREADY_APPLIED);
        verify(repository).finish(itemId, Outcome.ALREADY_APPLIED, true);
        verify(vacancies).markApplied(vacancyId);
        verify(repository).resolvePreviousApplicationAttention(run.accountId(), "123");
        verify(repository, never()).permit(any());
    }

    @Test
    void anotherUnknownAttemptDoesNotResolvePriorAttention() {
        prepare("IN_PROGRESS", Instant.now(), RunStatus.RUNNING, null);
        service.complete(itemId, Outcome.UNKNOWN);
        verify(repository).finish(itemId, Outcome.UNKNOWN, true);
        verify(repository, never()).resolvePreviousApplicationAttention(any(), anyString());
    }

    @Test
    void localProblemsDoNotPauseTheQueue() {
        for (var outcome : List.of(Outcome.QUESTIONNAIRE, Outcome.LETTER_REQUIRED, Outcome.RESUME_UNAVAILABLE,
                Outcome.VACANCY_UNAVAILABLE, Outcome.UNKNOWN, Outcome.NOT_SENT)) {
            reset(repository, vacancies);
            prepare("IN_PROGRESS", Instant.now(), RunStatus.RUNNING, null);
            when(repository.recordTechnicalFailure(runId, true)).thenReturn(1);
            service.complete(itemId, outcome);
            verify(repository, never()).status(any(), any());
            verify(repository).advance(eq(runId), anyInt());
        }
    }

    @Test
    void thirdConsecutiveTechnicalProblemPauses() {
        prepare("IN_PROGRESS", Instant.now(), RunStatus.RUNNING, null);
        when(repository.recordTechnicalFailure(runId, true)).thenReturn(3);
        service.complete(itemId, Outcome.UNKNOWN);
        verify(repository).status(runId, RunStatus.PAUSED_ERROR);
        verify(repository).advance(eq(runId), anyInt()); // Still completes if no work remains.
    }

    @Test
    void captchaAfterClickDoesNotEraseUncertainResult() {
        prepare("IN_PROGRESS", Instant.now(), RunStatus.RUNNING, null);
        service.complete(itemId, Outcome.UNKNOWN, QueueBlocker.CAPTCHA);
        verify(repository).finish(itemId, Outcome.UNKNOWN, true);
        verify(repository).status(runId, RunStatus.PAUSED_CAPTCHA);
    }

    @Test
    void globalProblemsPauseImmediately() {
        for (var outcome : List.of(Outcome.CAPTCHA, Outcome.AUTH, Outcome.RESUME_NOT_FOUND, Outcome.BROWSER_UNAVAILABLE)) {
            reset(repository, vacancies);
            prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
            service.complete(itemId, outcome);
            verify(repository).status(runId, outcome == Outcome.CAPTCHA ? RunStatus.PAUSED_CAPTCHA
                    : outcome == Outcome.AUTH ? RunStatus.PAUSED_AUTH : RunStatus.PAUSED_ERROR);
        }
    }

    @Test
    void accountLimitPausesWithoutClaimingAnotherVacancy() {
        prepare("QUEUED", null, RunStatus.RUNNING, null);
        var run = repository.find(runId).orElseThrow();
        when(repository.next(run.profileId())).thenReturn(Optional.of(
                new Task(itemId, run.profileId(), run.hhResumeId(), "123", "https://hh.ru/search/vacancy", 0, "")));
        when(repository.attemptsToday(run.accountId())).thenReturn((long) run.accountDailyLimit());
        assertTrue(service.claim(run.profileId()).isEmpty());
        verify(repository).status(runId, RunStatus.PAUSED_LIMIT);
        verify(repository, never()).claim(any());
    }

    @Test
    void resolvedAttentionIsExposedWithoutClaimingApplicationSuccess() {
        prepare("ATTENTION_REQUIRED", Instant.now(), RunStatus.COMPLETED, Outcome.SENT_LETTER_UNCONFIRMED);
        var item = repository.item(itemId);
        when(repository.item(itemId)).thenReturn(new Item(item.id(), item.runId(), item.vacancyId(), item.hhVacancyId(),
                item.vacancyTitle(), item.companyName(), item.status(), item.outcome(), item.attemptedAt(),
                item.submissionStartedAt(), item.hasQuestionnaire(), "RESOLVED"));
        var attention = service.attention(itemId, "RESOLVED");
        assertEquals("RESOLVED", attention.status());
        assertEquals(Outcome.SENT_LETTER_UNCONFIRMED, attention.outcome());
        assertFalse(attention.canContinueRun());
        verify(repository, never()).finish(any(), any(), anyBoolean());
    }

    @Test
    void historicalQuestionnaireDoesNotSkipTheCurrentDetailCheck() {
        prepare("QUEUED", null, RunStatus.RUNNING, null);
        var item = repository.item(itemId);
        var run = repository.find(runId).orElseThrow();
        when(repository.item(itemId)).thenReturn(new Item(item.id(), item.runId(), item.vacancyId(), item.hhVacancyId(),
                item.vacancyTitle(), item.companyName(), item.status(), item.outcome(), item.attemptedAt(),
                item.submissionStartedAt(), true, "OPEN"));
        var task = new Task(itemId, run.profileId(), run.hhResumeId(), "123", "https://hh.ru/search/vacancy", 0, "");
        when(repository.next(run.profileId())).thenReturn(Optional.of(task));
        assertEquals(Optional.of(task), service.claim(run.profileId()));
        verify(repository, never()).finish(any(), any(), anyBoolean());
    }

    @Test
    void aiRunCannotGetSubmissionPermitBeforeLetterIsPersisted() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        enableAi();
        assertFalse(service.permit(itemId));
        verify(repository, never()).permit(any());
    }

    @Test
    void fallbackMustMatchRunSnapshotAndBeSavedBeforeSubmission() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        enableAi("Резервное письмо");
        var letter = new PreparedLetter("Резервное письмо", "FALLBACK", "VACANCY_TEXT_MISSING", null);
        assertTrue(service.prepareLetter(itemId, letter));
        verify(repository).prepareLetter(itemId, letter);
        assertThrows(RuntimeException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Другой текст", "FALLBACK", "TIMEOUT", null)));
    }

    @Test
    void generatedLetterMustPreserveSnapshotTemplate() {
        prepare("IN_PROGRESS", null, RunStatus.RUNNING, null);
        enableAi();
        assertThrows(RuntimeException.class, () -> service.prepareLetter(itemId,
                new PreparedLetter("Без шапки и подвала", "GENERATED", null, UUID.randomUUID())));
        var letter = new PreparedLetter("Привет!\nРазрабатываю API.\nСпасибо!", "GENERATED", null, UUID.randomUUID());
        assertTrue(service.prepareLetter(itemId, letter));
        verify(repository).prepareLetter(itemId, letter);
    }

    @Test
    void pausedRunCannotStoreLetterOrObtainPermit() {
        prepare("IN_PROGRESS", null, RunStatus.PAUSED_USER, null);
        enableAi();
        assertFalse(service.prepareLetter(itemId, new PreparedLetter("", "FALLBACK", "TIMEOUT", null)));
        verify(repository, never()).prepareLetter(any(), any());
    }

    private void enableAi() {
        enableAi(repository.find(runId).orElseThrow().coverLetter());
    }

    private void enableAi(String coverLetter) {
        var run = repository.find(runId).orElseThrow();
        var ai = new org.hhclicker.backend.resume.model.AiLetterSettings(true, "Привет!\n#AI_CHANGE#\nСпасибо!", 1500);
        when(repository.find(runId)).thenReturn(Optional.of(new Run(run.id(), run.sequence(), run.accountId(), run.resumeId(),
                run.profileId(), run.hhResumeId(), run.resumeName(), run.status(), run.createdAt(), run.finishedAt(),
                run.dailyLimit(), run.accountDailyLimit(), run.pauseFromSeconds(), run.pauseToSeconds(), coverLetter,
                run.skipQuestionnaires(), run.applications(), ai)));
    }

    private void prepare(String status, Instant permit, RunStatus runStatus, Outcome outcome) {
        var item = new Item(itemId, runId, vacancyId, "123", "Developer", "Example", status, outcome,
                Instant.now(), permit, false, "OPEN");
        var run = new Run(runId, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "abcdef", "Resume",
                runStatus, Instant.now(), null, 1, 20, 10, 10, "", true, List.of(item));
        when(repository.item(itemId)).thenReturn(item);
        when(repository.find(runId)).thenReturn(Optional.of(run));
        var vacancy = mock(Vacancy.class);
        when(vacancy.status()).thenReturn(VacancyStatus.NEW);
        when(vacancy.queuedForApplication()).thenReturn(true);
        when(vacancies.findById(vacancyId)).thenReturn(new VacancyListing(vacancy, UUID.randomUUID()));
    }
}
