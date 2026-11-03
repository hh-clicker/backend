package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.Task;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Взятие вакансии в работу и одноразовое разрешение на отправку.
 */
class ApplicationQueueClaimTests extends ApplicationServiceTestSupport {

    private Task task() {
        return new Task(itemId, profileId, HH_RESUME_ID, HH_VACANCY_ID, SOURCE_URL, 0, "Обычное письмо");
    }

    @Test
    void emptyQueueIsClaimedUnderLockWithoutSideEffects() {
        when(repository.next(profileId)).thenReturn(Optional.empty());

        assertTrue(service.claim(profileId).isEmpty());

        verify(repository).lock();
        verify(repository, never()).claim(any());
        verify(repository, never()).releaseUncertainGuard(any(), anyString());
    }

    @Test
    void vacancyThatLeftTheQueueIsClosedInsteadOfBeingSentToTheAgent() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);
        stubVacancy(VacancyStatus.NEW, false);
        when(repository.next(profileId)).thenReturn(Optional.of(task()));

        assertTrue(service.claim(profileId).isEmpty());

        verify(repository).claim(itemId);
        verify(repository).finish(itemId, Outcome.NOT_SENT, false);
        verify(repository, never()).releaseUncertainGuard(any(), anyString());
    }

    @Test
    void vacancyClosedOnHhIsNotSentToTheAgent() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);
        stubVacancy(VacancyStatus.CLOSED, true);
        when(repository.next(profileId)).thenReturn(Optional.of(task()));

        assertTrue(service.claim(profileId).isEmpty());

        verify(repository).finish(itemId, Outcome.NOT_SENT, false);
    }

    @Test
    void vacancyHeldByAnotherAttemptIsClosedWithoutReleasingTheGuard() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);
        when(repository.next(profileId)).thenReturn(Optional.of(task()));
        when(repository.guarded(accountId, HH_VACANCY_ID, itemId)).thenReturn(true);

        assertTrue(service.claim(profileId).isEmpty());

        verify(repository).finish(itemId, Outcome.NOT_SENT, false);
        verify(repository, never()).releaseUncertainGuard(any(), anyString());
    }

    @Test
    void eligibleItemReceivesSubmissionPermitExactlyOnce() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        assertTrue(service.permit(itemId));

        verify(repository).permit(itemId);
    }

    @Test
    void permitIsDeniedForItemThatIsNotInProgress() {
        prepare("QUEUED", null, null, RunStatus.RUNNING);

        assertFalse(service.permit(itemId));

        verify(repository, never()).permit(any());
    }

    @Test
    void permitIsDeniedAfterSubmissionHasAlreadyStarted() {
        prepare("IN_PROGRESS", null, NOW, RunStatus.RUNNING);

        assertFalse(service.permit(itemId));

        verify(repository, never()).permit(any());
    }

    @Test
    void permitIsDeniedWhenAccountDailyLimitIsAlreadyReached() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);
        when(repository.attemptsToday(accountId)).thenReturn(20L);

        assertFalse(service.permit(itemId));

        verify(repository, never()).permit(any());
    }

    @Test
    void permitIsDeniedWhenVacancyLeftTheQueueBetweenClaimAndSubmission() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);
        stubVacancy(VacancyStatus.NEW, false);

        assertFalse(service.permit(itemId));

        verify(repository, never()).permit(any());
    }

    @Test
    void permitIsDeniedWhenDuplicateGuardIsHeldByAnotherItem() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);
        when(repository.guarded(accountId, HH_VACANCY_ID, itemId)).thenReturn(true);

        assertFalse(service.permit(itemId));

        verify(repository, never()).permit(any());
    }

    @Test
    void agentMayAskWhetherSubmissionIsStillAllowedWithoutConsumingThePermit() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        assertTrue(service.allowed(itemId));

        verify(repository, never()).permit(any());
        verify(repository, never()).finish(any(), any(), anyBoolean());
    }

    @Test
    void submissionIsNotAllowedWhileTheRunIsPaused() {
        prepare("IN_PROGRESS", null, null, RunStatus.PAUSED_USER);

        assertFalse(service.allowed(itemId));
        assertFalse(service.permit(itemId));
    }
}
