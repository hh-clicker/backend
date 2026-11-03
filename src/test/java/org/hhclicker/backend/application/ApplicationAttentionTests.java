package org.hhclicker.backend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hhclicker.backend.application.model.ApplicationModels.Item;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Задачи внимания: закрытие, пропуск и признак возможности продолжить очередь.
 */
class ApplicationAttentionTests extends ApplicationServiceTestSupport {

    @BeforeEach
    void setUpVacancy() {
        stubVacancy(VacancyStatus.NEW, true);
    }

    @Test
    void unknownAcknowledgementStatusIsRejectedBeforeTouchingTheItem() {
        assertThrows(ApplicationException.class, () -> service.attention(itemId, "DONE"));

        verify(repository, never()).acknowledge(any(), anyString());
        verify(repository, never()).finishIfIdle(any());
    }

    @Test
    void itemWithoutAnOpenQuestionCannotBeAcknowledged() {
        Item finished = item("SUCCEEDED", Outcome.SENT, NOW);
        stub(finished, run(RunStatus.COMPLETED, List.of(finished)));

        assertThrows(ApplicationException.class, () -> service.attention(itemId, "RESOLVED"));

        verify(repository, never()).acknowledge(any(), anyString());
    }

    @Test
    void skippingTheTaskAlsoMovesItsVacancyToSkipped() {
        Item before = item("ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, null, null, "OPEN");
        Item after = item("ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, null, null, "SKIPPED");
        when(repository.item(itemId)).thenReturn(before, after);
        when(repository.find(runId)).thenReturn(Optional.of(run(RunStatus.PAUSED_ERROR, List.of(before))));

        var attention = service.attention(itemId, "SKIPPED");

        assertEquals("SKIPPED", attention.status());
        assertEquals(Outcome.QUESTIONNAIRE, attention.outcome());
        verify(vacancySelection).skip(vacancyId);
        verify(repository).acknowledge(itemId, "SKIPPED");
        verify(repository).finishIfIdle(runId);
        verify(repository, never()).finish(any(), any(), anyBoolean());
        verify(repository, never()).releaseUncertainGuard(any(), anyString());
        verify(vacancies, never()).markApplied(any());
    }

    @Test
    void failedVacancySkipDoesNotHideTheAttentionTask() {
        Item waiting = item("ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, null, null, "OPEN");
        when(repository.item(itemId)).thenReturn(waiting);
        doThrow(new IllegalStateException("state changed")).when(vacancySelection).skip(vacancyId);

        assertThrows(IllegalStateException.class, () -> service.attention(itemId, "SKIPPED"));

        verify(repository, never()).acknowledge(any(), anyString());
        verify(repository, never()).finishIfIdle(any());
    }

    @Test
    void inactiveVacancyCannotBeOfferedForSkipping() {
        Item waiting = item("ATTENTION_REQUIRED", Outcome.SENT_LETTER_UNCONFIRMED, NOW, null, "OPEN");
        stubVacancy(VacancyStatus.ALREADY_APPLIED, false);
        when(repository.list(resumeId)).thenReturn(List.of(run(RunStatus.COMPLETED, List.of(waiting))));

        assertFalse(service.attention(resumeId).getFirst().canSkipVacancy());
    }

    @ParameterizedTest
    @CsvSource({
            "QUESTIONNAIRE, QUESTIONNAIRE",
            "CAPTCHA, CAPTCHA",
            "AUTH, AUTH",
            "NOT_SENT, ERROR",
            "UNKNOWN, ERROR",
            "LETTER_REQUIRED, ERROR",
            "SENT_LETTER_UNCONFIRMED, ERROR",
            "SENT_LETTER_REJECTED_ALREADY_VIEWED, ERROR"
    })
    void attentionKindIsDerivedFromTheOutcome(Outcome outcome, String expectedKind) {
        Item waiting = item("ATTENTION_REQUIRED", outcome, NOW, null, "OPEN");
        when(repository.list(resumeId)).thenReturn(List.of(run(RunStatus.PAUSED_ERROR, List.of(waiting))));

        var tasks = service.attention(resumeId);

        assertEquals(1, tasks.size());
        assertEquals(expectedKind, tasks.getFirst().kind());
        assertEquals(outcome.title(), tasks.getFirst().title());
        assertEquals(outcome.message(), tasks.getFirst().description());
    }

    @Test
    void pausedRunWithRemainingQueueCanBeContinued() {
        Item waiting = item("ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, null, null, "OPEN");
        Item queued = queuedItem();
        when(repository.list(resumeId)).thenReturn(List.of(run(RunStatus.PAUSED_ERROR, List.of(waiting, queued))));

        assertTrue(service.attention(resumeId).getFirst().canContinueRun());
    }

    @Test
    void runningRunIsNotOfferedForContinuation() {
        Item waiting = item("ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, null, null, "OPEN");
        when(repository.list(resumeId)).thenReturn(List.of(run(RunStatus.RUNNING, List.of(waiting, queuedItem()))));

        assertFalse(service.attention(resumeId).getFirst().canContinueRun());
    }

    @Test
    void pausedRunWithoutRemainingQueueIsNotOfferedForContinuation() {
        Item waiting = item("ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, null, null, "OPEN");
        when(repository.list(resumeId)).thenReturn(List.of(run(RunStatus.PAUSED_ERROR, List.of(waiting))));

        assertFalse(service.attention(resumeId).getFirst().canContinueRun());
    }

    @Test
    void runWithAnItemStillInProgressIsNotOfferedForContinuation() {
        Item waiting = item("ATTENTION_REQUIRED", Outcome.QUESTIONNAIRE, null, null, "OPEN");
        Item running = new Item(UUID.randomUUID(), runId, UUID.randomUUID(), "222", "Java-разработчик",
                "Синтетическая компания", "IN_PROGRESS", null, NOW, null, false, "OPEN");
        when(repository.list(resumeId)).thenReturn(List.of(
                run(RunStatus.PAUSED_ERROR, List.of(waiting, queuedItem(), running))));

        assertFalse(service.attention(resumeId).getFirst().canContinueRun());
    }

    @Test
    void onlyItemsWaitingForTheUserAreExposedAsTasks() {
        Item waiting = item("ATTENTION_REQUIRED", Outcome.UNKNOWN, NOW, null, "OPEN");
        Item succeeded = new Item(UUID.randomUUID(), runId, UUID.randomUUID(), "333", "Java-разработчик",
                "Синтетическая компания", "SUCCEEDED", Outcome.SENT, NOW, NOW, false, "OPEN");
        when(repository.list(resumeId)).thenReturn(List.of(
                run(RunStatus.PAUSED_ERROR, List.of(waiting, succeeded, queuedItem()))));

        var tasks = service.attention(resumeId);

        assertEquals(1, tasks.size());
        assertEquals(waiting.id(), tasks.getFirst().applicationId());
        assertEquals(HH_VACANCY_ID, tasks.getFirst().hhVacancyId());
        assertEquals("3529", tasks.getFirst().employerId());
    }

    private Item queuedItem() {
        return new Item(UUID.randomUUID(), runId, UUID.randomUUID(), "111", "Java-разработчик",
                "Синтетическая компания", "QUEUED", null, null, null, false, "OPEN");
    }
}
