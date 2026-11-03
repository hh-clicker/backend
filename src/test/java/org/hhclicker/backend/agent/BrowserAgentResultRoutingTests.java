package org.hhclicker.backend.agent;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.agent.exception.BrowserAgentCommandConflictException;
import org.hhclicker.backend.agent.model.BrowserAgentCommand;
import org.hhclicker.backend.agent.model.BrowserAgentCommandResultStatus;
import org.hhclicker.backend.agent.model.BrowserAgentCommandStatus;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.QueueBlocker;
import org.hhclicker.backend.search.exception.InvalidSearchDataException;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.service.PreparedSearchScan;
import org.hhclicker.backend.search.service.SearchProfile;
import org.hhclicker.backend.vacancy.catalog.service.NormalizedVacancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Маршрутизация результатов команд в account, resume, search и application.
 */
class BrowserAgentResultRoutingTests extends BrowserAgentTestSupport {

    private static final String SCAN_URL =
            "https://hh.ru/search/vacancy?items_on_page=100&order_by=publication_time&page=0&text=java";

    private NormalizedVacancy vacancy(String hhVacancyId) {
        return new NormalizedVacancy(hhVacancyId, "Java-разработчик", "3529", "Синтетическая компания",
                null, "Москва", "Удалённо", "1-3 года", null, "", List.of(), null, false, false, 0);
    }

    private BrowserAgentCommand claimedScan(int pagesLimit) {
        VacancySearch search = new VacancySearch(searchId, resumeId, "Java в Москве", SCAN_URL, "a".repeat(64),
                pagesLimit, SearchStatus.SCANNING, 0, 0, null, null, START, START);
        when(searches.accountIdForSearch(searchId)).thenReturn(accountId);
        when(searches.scan(searchId)).thenReturn(
                new PreparedSearchScan(new SearchProfile(search, "Java-разработчик"), SCAN_URL));
        service.scanSearch(searchId);
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private BrowserAgentCommand claimedApply() {
        when(applications.claim(profileId)).thenReturn(Optional.of(task()));
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private BrowserAgentCommand claimedInspection() {
        questionnaireVacancyIsReady();
        service.verifyQuestionnaireApplication(vacancyId);
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    private BrowserAgentCommand claimedOpenBrowser() {
        service.open(profile);
        return service.claimNext(AGENT_ID).orElseThrow();
    }

    @Test
    void authorizationResultIsForwardedToTheAccountModule() {
        BrowserAgentCommand claimed = claimedOpenBrowser();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                authorization(AuthorizationStatus.REQUIRED, "Нужен вход"), null, null, null, null, null);

        verify(authorizationService).record(profileId, AuthorizationStatus.UNKNOWN);
        verify(authorizationService).record(profileId, AuthorizationStatus.REQUIRED);
    }

    /**
     * Список резюме не проталкивается в модуль {@code resume}: сервис только кладёт его в ответ
     * команды, а импортом занимается отдельный HTTP-сценарий. Тест фиксирует этот контракт.
     */
    @Test
    void discoveredResumesAreReturnedInsideTheCommandResult() {
        service.discoverResumes(profile);
        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(),
                List.of(resume(HH_RESUME_ID, null), resume("0123456789abcdef0123", null)),
                null, null, null, null);

        assertEquals(2, completed.resumes().size());
        assertEquals(HH_RESUME_ID, completed.resumes().getFirst().hhResumeId());
        verifyNoInteractions(searches);
    }

    @Test
    void resumeTextIsReturnedInsideTheCommandResult() {
        service.readResumeText(profile, HH_RESUME_ID);
        BrowserAgentCommand claimed = service.claimNext(AGENT_ID).orElseThrow();

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(),
                List.of(resume(HH_RESUME_ID, "Полный текст резюме")), null, null, null, null);

        assertEquals("Полный текст резюме", completed.resumes().getFirst().plainText());
    }

    @Test
    void foundVacanciesAreForwardedToTheSearchModule() {
        BrowserAgentCommand claimed = claimedScan(1);
        List<NormalizedVacancy> found = List.of(vacancy("118000001"), vacancy("118000002"));

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, found, null, null, null);

        verify(searches).completeScan(searchId, found);
        verify(searches, never()).failScan(any(), anyString());
        assertEquals(BrowserAgentCommandStatus.SUCCEEDED, completed.status());
    }

    @Test
    void scanResultFromAnotherAgentIsRejectedWithoutSideEffects() {
        BrowserAgentCommand claimed = claimedScan(1);
        clearInvocations(statusService, authorizationService, searches, profiles, applications);

        assertThrows(BrowserAgentCommandConflictException.class, () -> service.complete(
                claimed.id(), OTHER_AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED,
                null, authorized(), null, List.of(vacancy("118000001")), null, null, null));

        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(claimed.id()).status());
        verify(searches, never()).completeScan(any(), any());
        verify(searches, never()).failScan(any(), anyString());
        verifyNoInteractions(statusService, authorizationService, profiles, applications);
    }

    @Test
    void scanResultBiggerThanTheRequestedPagesIsRefused() {
        BrowserAgentCommand claimed = claimedScan(1);
        List<NormalizedVacancy> tooMany = new ArrayList<>();
        for (int index = 0; index <= 100; index++) {
            tooMany.add(vacancy(String.valueOf(118_000_000 + index)));
        }

        assertInvalidAgentData(() -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, tooMany, null, null, null));

        verify(searches, never()).completeScan(any(), any());
        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(claimed.id()).status());
    }

    @Test
    void confirmedScanWithoutAResultListIsRefused() {
        BrowserAgentCommand claimed = claimedScan(1);

        assertInvalidAgentData(() -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, null, null, null, null));
    }

    @Test
    void scanThatLostTheSessionIsFailedWithTheAuthorizationMessage() {
        BrowserAgentCommand claimed = claimedScan(1);

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null,
                authorization(AuthorizationStatus.REQUIRED, "Сессия HH истекла"), null, List.of(), null, null, null);

        assertEquals(BrowserAgentCommandStatus.FAILED, completed.status());
        assertEquals("Сессия HH истекла", completed.errorMessage());
        verify(searches).failScan(searchId, "Сессия HH истекла");
        verify(searches, never()).completeScan(any(), any());
        verify(authorizationService).record(profileId, AuthorizationStatus.REQUIRED);
    }

    @Test
    void failedScanIsReportedToTheSearchModule() {
        BrowserAgentCommand claimed = claimedScan(1);

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.FAILED,
                "Агент потерял страницу выдачи", null, null, null, null, null, null);

        verify(searches).failScan(searchId, "Агент потерял страницу выдачи");
    }

    @Test
    void rejectionFromTheSearchModuleTurnsTheScanIntoAFailure() {
        BrowserAgentCommand claimed = claimedScan(1);
        doThrow(new InvalidSearchDataException("Сканирование уже завершено. Результат не сохранён."))
                .when(searches).completeScan(any(), any());

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null,
                List.of(vacancy("118000001")), null, null, null);

        assertEquals(BrowserAgentCommandStatus.FAILED, completed.status());
        assertEquals("Сканирование уже завершено. Результат не сохранён.", completed.errorMessage());
        verify(searches).failScan(searchId, "Сканирование уже завершено. Результат не сохранён.");
    }

    @Test
    void scanResultCannotBeProcessedConcurrentlyOrPublishedTwice() throws Exception {
        BrowserAgentCommand claimed = claimedScan(1);
        List<NormalizedVacancy> found = List.of(vacancy("118000001"));
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch allowPersistenceToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            persistenceStarted.countDown();
            if (!allowPersistenceToFinish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release scan persistence");
            }
            return null;
        }).when(searches).completeScan(searchId, found);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstCompletion = executor.submit(() -> service.complete(claimed.id(), AGENT_ID,
                    BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, found,
                    null, null, null));
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS),
                    "первая обработка должна войти в persistence до повторной доставки");

            assertConflict(() -> service.complete(claimed.id(), AGENT_ID,
                    BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, found,
                    null, null, null));
            assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(claimed.id()).status());

            allowPersistenceToFinish.countDown();
            BrowserAgentCommand completed = firstCompletion.get(5, TimeUnit.SECONDS);
            assertEquals(BrowserAgentCommandStatus.SUCCEEDED, completed.status());
            assertSame(completed, service.complete(claimed.id(), AGENT_ID,
                    BrowserAgentCommandResultStatus.SUCCEEDED, null, authorized(), null, found,
                    null, null, null));
        } finally {
            allowPersistenceToFinish.countDown();
            executor.shutdownNow();
        }

        verify(searches, times(1)).completeScan(searchId, found);
        verify(searches, never()).failScan(any(), anyString());
    }

    @Test
    void redeliveredFailedScanDoesNotFailTheSearchTwice() {
        BrowserAgentCommand claimed = claimedScan(1);

        BrowserAgentCommand failed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.FAILED, "Страница потеряна",
                null, null, null, null, null, null);
        BrowserAgentCommand redelivered = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.FAILED, "Страница потеряна",
                null, null, null, null, null, null);

        assertSame(failed, redelivered);
        verify(searches, times(1)).failScan(searchId, "Страница потеряна");
        verify(searches, never()).completeScan(any(), any());
    }

    @Test
    void confirmedReadOnlyInspectionMarksTheQuestionnaireVacancyApplied() {
        BrowserAgentCommand claimed = claimedInspection();

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null,
                Outcome.ALREADY_APPLIED, null, null);

        verify(vacancySelection).markQuestionnaireApplied(vacancyId);
        verify(authorizationService).record(profileId, AuthorizationStatus.AUTHORIZED);
        assertEquals(Outcome.ALREADY_APPLIED, completed.application());
    }

    @Test
    void absentApplicationDoesNotChangeTheQuestionnaireVacancy() {
        BrowserAgentCommand claimed = claimedInspection();

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null,
                Outcome.NOT_SENT, null, null);

        verify(vacancySelection, never()).markQuestionnaireApplied(any());
        assertEquals(Outcome.NOT_SENT, completed.application());
    }

    @Test
    void readOnlyInspectionRejectsAnOutcomeThatCouldOnlyFollowASubmission() {
        BrowserAgentCommand claimed = claimedInspection();

        assertInvalidAgentData(() -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null,
                Outcome.SENT, null, null));

        verify(vacancySelection, never()).markQuestionnaireApplied(any());
    }

    @Test
    void applicationOutcomeAndVacancyTextAreForwardedToTheApplicationModule() {
        BrowserAgentCommand claimed = claimedApply();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, Outcome.SENT, null, "Описание вакансии");

        verify(applications).complete(itemId, Outcome.SENT, null, "Описание вакансии");
        verify(authorizationService).record(profileId, AuthorizationStatus.AUTHORIZED);
    }

    @Test
    void queueBlockersReportedByTheApplicationUpdateTheSessionState() {
        BrowserAgentCommand claimed = claimedApply();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, Outcome.NOT_SENT, QueueBlocker.AUTH, null);

        verify(applications).complete(itemId, Outcome.NOT_SENT, QueueBlocker.AUTH, null);
        verify(authorizationService).record(profileId, AuthorizationStatus.REQUIRED);
    }

    @Test
    void captchaKeepsTheSessionStateUnknown() {
        BrowserAgentCommand claimed = claimedApply();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, Outcome.CAPTCHA, null, null);

        verify(authorizationService).record(profileId, AuthorizationStatus.UNKNOWN);
        verify(authorizationService, never()).record(profileId, AuthorizationStatus.AUTHORIZED);
    }

    @Test
    void authorizationOutcomeMarksTheSessionAsRequiredEvenWithoutABlocker() {
        BrowserAgentCommand claimed = claimedApply();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, Outcome.AUTH, null, null);

        verify(authorizationService).record(profileId, AuthorizationStatus.REQUIRED);
    }

    @Test
    void captchaBlockerMarksTheSessionUnknownEvenForANonCaptchaOutcome() {
        BrowserAgentCommand claimed = claimedApply();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, Outcome.NOT_SENT, QueueBlocker.CAPTCHA, null);

        verify(authorizationService).record(profileId, AuthorizationStatus.UNKNOWN);
    }

    @Test
    void ordinaryUnsuccessfulOutcomeDoesNotInventAnAuthorizationTransition() {
        BrowserAgentCommand claimed = claimedApply();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, Outcome.NOT_SENT, null, null);

        verifyNoInteractions(authorizationService);
    }

    @Test
    void failedApplicationCommandReportsTheAttemptAsLost() {
        BrowserAgentCommand claimed = claimedApply();

        service.complete(claimed.id(), AGENT_ID, BrowserAgentCommandResultStatus.FAILED,
                "Chrome закрылся", null, null, null, null, null, null);

        verify(applications).lost(itemId);
        verify(applications, never()).complete(any(), any(), any(), anyString());
    }

    /**
     * Успешная команда без результата отклика тоже считается потерянной попыткой.
     */
    @Test
    void successfulApplicationCommandWithoutAnOutcomeIsAlsoTreatedAsLost() {
        BrowserAgentCommand claimed = claimedApply();

        BrowserAgentCommand completed = service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, null, null, null);

        verify(applications).lost(itemId);
        assertEquals(BrowserAgentCommandStatus.SUCCEEDED, completed.status(),
                "команда всё равно закрывается как успешная");
    }

    @Test
    void browserStateIsUpdatedOnlyByTheBrowserLifecycleCommands() {
        BrowserAgentCommand opened = claimedOpenBrowser();
        service.complete(opened.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                authorized(), null, null, null, null, null);
        verify(statusService).updateBrowserState(true, profileId);

        service.close();
        BrowserAgentCommand closed = service.claimNext(AGENT_ID).orElseThrow();
        service.complete(closed.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, null, null, null);
        verify(statusService).updateBrowserState(false, null);

        service.openUrl(profile, "https://hh.ru/applicant/resumes");
        BrowserAgentCommand url = service.claimNext(AGENT_ID).orElseThrow();
        service.complete(url.id(), AGENT_ID, BrowserAgentCommandResultStatus.SUCCEEDED, null,
                null, null, null, null, null, null);
        verify(statusService, times(2)).updateBrowserState(anyBoolean(), any());
    }

    @Test
    void failedBrowserCommandDoesNotChangeTheBrowserState() {
        BrowserAgentCommand opened = claimedOpenBrowser();

        service.complete(opened.id(), AGENT_ID, BrowserAgentCommandResultStatus.FAILED,
                "Chrome не запустился", null, null, null, null, null, null);

        verify(statusService, never()).updateBrowserState(anyBoolean(), any());
    }

    @Test
    void unexpectedFailureOfANeighbourLeavesTheCommandRunningAndRetryable() {
        BrowserAgentCommand claimed = claimedApply();
        doThrow(new IllegalStateException("БД недоступна"))
                .when(applications).complete(any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> service.complete(claimed.id(), AGENT_ID,
                BrowserAgentCommandResultStatus.SUCCEEDED, null, null, null, null, Outcome.SENT, null, null));

        assertEquals(BrowserAgentCommandStatus.IN_PROGRESS, service.findById(claimed.id()).status(),
                "команда остаётся в работе и результат можно доставить повторно");
        verify(statusService, never()).updateBrowserState(anyBoolean(), any());
    }
}
