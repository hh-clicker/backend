package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.resume.model.AiLetterSettings;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Приём результата отклика: статус item, защита от повтора, счётчик ошибок и остановки очереди.
 */
class ApplicationOutcomeTests extends ApplicationServiceTestSupport {

    @Test
    void employerViewRejectionHasASpecificMessageWithoutChangingTheGenericFallback() {
        assertEquals(
                "Отклик отправлен. Прикрепление письма не подтверждено — проверьте на HH. Повторного отклика не будет.",
                Outcome.SENT_LETTER_UNCONFIRMED.message());
        assertEquals(
                "Отклик отправлен, но сопроводительное письмо не прикрепилось: работодатель уже просмотрел отклик, и HH больше не позволяет его изменить. Повторного отклика не будет.",
                Outcome.SENT_LETTER_REJECTED_ALREADY_VIEWED.message());
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"STOPPED", "TEXT_LOADED", "TEXT_UNAVAILABLE", "VACANCY_ARCHIVED"}, mode = EnumSource.Mode.EXCLUDE)
    void guardAndCatalogEffectsFollowTheOutcomeWhenSubmissionWasPermitted(Outcome outcome) {
        prepare("IN_PROGRESS", null, NOW, RunStatus.RUNNING);

        service.complete(itemId, outcome);

        boolean expectedGuard = outcome.applied() || outcome == Outcome.UNKNOWN;
        verify(repository).finish(itemId, outcome, expectedGuard);
        if (outcome.applied()) {
            verify(vacancies).markApplied(vacancyId);
            verify(repository).resolvePreviousApplicationAttention(accountId, HH_VACANCY_ID);
        } else {
            verify(vacancies, never()).markApplied(any());
            verify(repository, never()).resolvePreviousApplicationAttention(any(), anyString());
        }
        if (outcome == Outcome.QUESTIONNAIRE) {
            verify(vacancies).markQuestionnaire(vacancyId);
        } else {
            verify(vacancies, never()).markQuestionnaire(any());
        }
        verify(repository).recordTechnicalFailure(runId, outcome == Outcome.NOT_SENT || outcome == Outcome.UNKNOWN || outcome == Outcome.AI_LETTER_FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"SENT", "SENT_LETTER_UNCONFIRMED", "SENT_LETTER_REJECTED_ALREADY_VIEWED"})
    void confirmedResultWithoutSubmissionPermitIsDowngradedToUnknown(Outcome reported) {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        service.complete(itemId, reported);

        verify(repository).finish(itemId, Outcome.UNKNOWN, false);
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void confirmedExistingApplicationGuardsAgainstRepeatEvenWithoutPermit() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        service.complete(itemId, Outcome.ALREADY_APPLIED);

        verify(repository).finish(itemId, Outcome.ALREADY_APPLIED, true);
        verify(vacancies).markApplied(vacancyId);
    }

    @Test
    void archiveBeforeSubmissionClosesVacancyWithoutSavingTextOrCreatingAttention() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        service.complete(itemId, Outcome.VACANCY_ARCHIVED, null, "Archive description must not be stored");

        verify(repository).finish(itemId, Outcome.VACANCY_ARCHIVED, false);
        verify(vacancySelection).closeFromHh(vacancyId);
        verify(vacancies, never()).saveText(any(), any());
        verify(repository).recordTechnicalFailure(runId, false);
        verify(repository, never()).status(any(), any());
    }

    @Test
    void archiveReportedAfterSubmissionPermitRemainsUncertain() {
        prepare("IN_PROGRESS", null, NOW, RunStatus.RUNNING);

        service.complete(itemId, Outcome.VACANCY_ARCHIVED);

        verify(repository).finish(itemId, Outcome.UNKNOWN, true);
        verify(vacancySelection, never()).closeFromHh(any());
    }

    @Test
    void repeatedDeliveryOfAFinishedItemChangesNothing() {
        Item finished = item("SUCCEEDED", Outcome.SENT, NOW);
        stub(finished, run(RunStatus.COMPLETED, List.of(finished)));

        service.complete(itemId, Outcome.UNKNOWN, QueueBlocker.CAPTCHA, "новое описание");

        verify(repository, never()).finish(any(), any(), anyBoolean());
        verify(repository, never()).status(any(), any());
        verify(repository, never()).advance(any(), anyInt());
        verify(vacancies, never()).saveText(any(), anyString());
    }

    @Test
    void stoppedItemIsReturnedToTheQueueWithoutGuardOrTechnicalFailure() {
        prepare("IN_PROGRESS", null, NOW, RunStatus.PAUSED_USER);

        service.complete(itemId, Outcome.STOPPED);

        verify(repository).retry(itemId);
        verify(repository).advance(runId, 0);
        verify(repository, never()).finish(any(), any(), anyBoolean());
        verify(repository, never()).recordTechnicalFailure(any(), anyBoolean());
        verify(repository, never()).status(any(), any());
    }

    @Test
    void explicitBlockerWinsOverTheOutcomeDerivedOne() {
        prepare("IN_PROGRESS", null, NOW, RunStatus.RUNNING);

        service.complete(itemId, Outcome.QUESTIONNAIRE, QueueBlocker.AUTH);

        verify(repository).status(runId, RunStatus.PAUSED_AUTH);
    }

    @ParameterizedTest
    @EnumSource(value = QueueBlocker.class, names = {"BROWSER", "RESUME"})
    void browserAndResumeBlockersPauseTheQueueWithAGenericError(QueueBlocker blocker) {
        prepare("IN_PROGRESS", null, NOW, RunStatus.RUNNING);

        service.complete(itemId, Outcome.NOT_SENT, blocker);

        verify(repository).status(runId, RunStatus.PAUSED_ERROR);
    }

    @Test
    void twoConsecutiveTechnicalFailuresKeepTheQueueRunning() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);
        when(repository.recordTechnicalFailure(runId, true)).thenReturn(2);

        service.complete(itemId, Outcome.NOT_SENT);

        verify(repository, never()).status(any(), any());
        verify(repository).advance(eq(runId), anyInt());
    }

    @Test
    void expectedManualResultResetsTheConsecutiveFailureCounter() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        service.complete(itemId, Outcome.QUESTIONNAIRE);

        verify(repository).recordTechnicalFailure(runId, false);
    }

    @Test
    void successfulResultResetsTheConsecutiveFailureCounter() {
        prepare("IN_PROGRESS", null, NOW, RunStatus.RUNNING);

        service.complete(itemId, Outcome.SENT);

        verify(repository).recordTechnicalFailure(runId, false);
    }

    @Test
    void alreadyPausedRunIsNotPausedAgainByALateResult() {
        prepare("IN_PROGRESS", null, NOW, RunStatus.PAUSED_USER);

        service.complete(itemId, Outcome.AUTH);

        verify(repository).finish(itemId, Outcome.AUTH, false);
        verify(repository, never()).status(any(), any());
    }

    @Test
    void nextItemIsScheduledInsideTheConfiguredPauseRange() {
        Item inProgress = item("IN_PROGRESS", null, NOW);
        Run withWidePause = new Run(runId, 1, accountId, resumeId, profileId, HH_RESUME_ID, "Java-разработчик",
                RunStatus.RUNNING, NOW, null, 20, 20, 30, 90, "Обычное письмо", true, List.of(inProgress),
                AiLetterSettings.defaults());
        stub(inProgress, withWidePause);
        stubVacancy(org.hhclicker.backend.vacancy.catalog.model.VacancyStatus.NEW, true);

        service.complete(itemId, Outcome.SENT);

        ArgumentCaptor<Integer> seconds = ArgumentCaptor.forClass(Integer.class);
        verify(repository).advance(eq(runId), seconds.capture());
        assertTrue(seconds.getValue() >= 30 && seconds.getValue() <= 90,
                () -> "пауза вне диапазона запуска: " + seconds.getValue());
    }

    @Test
    void lostCommandBeforePermitDoesNotClaimSubmissionAndStopsTheQueue() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        service.lost(itemId);

        verify(repository).finish(itemId, Outcome.NOT_SENT, false);
        verify(repository).status(runId, RunStatus.PAUSED_ERROR);
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void lostCommandAfterPermitKeepsTheUncertainGuard() {
        prepare("IN_PROGRESS", null, NOW, RunStatus.RUNNING);

        service.lost(itemId);

        verify(repository).finish(itemId, Outcome.UNKNOWN, true);
        verify(repository).status(runId, RunStatus.PAUSED_ERROR);
    }

    @Test
    void queueRecoveryAfterRestartRunsUnderTheApplicationLock() {
        service.recover();

        var order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).lock();
        order.verify(repository).recover();
    }

    @Test
    void missingRunForFinishedItemIsReportedInsteadOfSilentlyIgnored() {
        Item inProgress = item("IN_PROGRESS", null, null);
        when(repository.item(itemId)).thenReturn(inProgress);
        when(repository.find(runId)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                org.hhclicker.backend.shared.exception.ApplicationException.class,
                () -> service.complete(itemId, Outcome.SENT));

        verify(repository, never()).finish(any(), any(), anyBoolean());
    }
}
