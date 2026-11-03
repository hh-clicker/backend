package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Переходы запуска: RUNNING / PAUSED_USER / CANCELLED / COMPLETED и запрещённые цели.
 */
class ApplicationRunStatusTests extends ApplicationServiceTestSupport {

    @Test
    void resumingRunWithoutRemainingWorkCompletesItInstead() {
        var finished = item("SUCCEEDED", Outcome.SENT, NOW);
        when(repository.find(runId)).thenReturn(Optional.of(run(RunStatus.PAUSED_ERROR, List.of(finished))));

        service.changeStatus(runId, RunStatus.RUNNING);

        verify(repository).status(runId, RunStatus.COMPLETED);
        verify(repository, never()).status(runId, RunStatus.RUNNING);
        verify(repository, never()).advance(any(), anyInt());
    }

    @Test
    void resumingWithExhaustedDailyBudgetIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.of(run(RunStatus.PAUSED_LIMIT, List.of(item("QUEUED", null, null)))));
        stubResume(20, "Синтетический текст резюме");
        when(repository.attemptsToday(accountId)).thenReturn(20L);

        assertConflict(() -> service.changeStatus(runId, RunStatus.RUNNING));

        verify(repository, never()).status(any(), any());
        verify(repository, never()).advance(any(), anyInt());
    }

    @Test
    void resumingResetsFailureCounterAndSchedulesTheQueueImmediately() {
        when(repository.find(runId)).thenReturn(Optional.of(run(RunStatus.PAUSED_ERROR, List.of(item("QUEUED", null, null)))));
        stubResume(20, "Синтетический текст резюме");

        service.changeStatus(runId, RunStatus.RUNNING);

        verify(repository).status(runId, RunStatus.RUNNING);
        verify(repository).recordTechnicalFailure(runId, false);
        verify(repository).advance(runId, 0);
    }

    @Test
    void pausingByUserKeepsTheFailureCounter() {
        when(repository.find(runId)).thenReturn(Optional.of(run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)))));

        service.changeStatus(runId, RunStatus.PAUSED_USER);

        verify(repository).status(runId, RunStatus.PAUSED_USER);
        verify(repository, never()).recordTechnicalFailure(any(), anyBoolean());
        verify(repository).advance(runId, 0);
    }

    @Test
    void cancellationIsAllowedFromPausedState() {
        when(repository.find(runId)).thenReturn(Optional.of(run(RunStatus.PAUSED_AUTH, List.of(item("QUEUED", null, null)))));

        service.changeStatus(runId, RunStatus.CANCELLED);

        verify(repository).status(runId, RunStatus.CANCELLED);
        verify(repository, never()).recordTechnicalFailure(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"PAUSED_CAPTCHA", "PAUSED_AUTH", "PAUSED_ERROR", "PAUSED_LIMIT", "PAUSED_AI_LIMIT", "COMPLETED"})
    void statusesReservedForTheBackendCannotBeRequestedManually(RunStatus requested) {
        when(repository.find(runId)).thenReturn(Optional.of(run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)))));

        assertConflict(() -> service.changeStatus(runId, requested));

        verify(repository, never()).status(any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "COMPLETED, RUNNING",
            "COMPLETED, CANCELLED",
            "CANCELLED, RUNNING",
            "CANCELLED, PAUSED_USER"
    })
    void terminalRunRejectsEveryDifferentRequestedTransition(RunStatus current, RunStatus requested) {
        when(repository.find(runId)).thenReturn(Optional.of(
                run(current, List.of(item("SUCCEEDED", Outcome.SENT, NOW)))));

        assertConflict(() -> service.changeStatus(runId, requested));

        verify(repository, never()).status(any(), any());
        verify(repository, never()).advance(any(), anyInt());
        verify(repository, never()).recordTechnicalFailure(any(), anyBoolean());
    }

    @Test
    void repeatingTerminalStatusIsIdempotent() {
        var cancelled = run(RunStatus.CANCELLED, List.of(item("CANCELLED", Outcome.STOPPED, null)));
        when(repository.find(runId)).thenReturn(Optional.of(cancelled));

        assertSame(cancelled, service.changeStatus(runId, RunStatus.CANCELLED));

        verify(repository, never()).status(any(), any());
    }

    @Test
    void missingRunIsReportedAsConflictWithoutTouchingState() {
        when(repository.find(runId)).thenReturn(Optional.empty());

        assertConflict(() -> service.changeStatus(runId, RunStatus.PAUSED_USER));

        verify(repository, never()).status(any(), any());
    }

    private void assertConflict(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(ApplicationException.class, executable);
        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
    }
}
