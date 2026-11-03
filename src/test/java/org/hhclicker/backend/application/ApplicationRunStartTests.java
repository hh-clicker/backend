package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.Start;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Создание запуска: идемпотентность по requestId, проверки очереди и дневного бюджета.
 */
class ApplicationRunStartTests extends ApplicationServiceTestSupport {

    private final UUID secondVacancyId = UUID.randomUUID();

    @Test
    void createsRunSnapshotAndOrderedQueue() {
        stubResume(20, "Синтетический текст резюме");
        stubVacancy(VacancyStatus.NEW, true);
        stubVacancy(secondVacancyId, "87654321", VacancyStatus.NEW, true);
        var created = run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)));
        when(repository.find(runId)).thenReturn(Optional.empty(), Optional.of(created));

        assertSame(created, service.start(start(List.of(vacancyId, secondVacancyId))));

        var order = inOrder(repository);
        order.verify(repository).lock();
        order.verify(repository).create(any(), eq(accountId), eq(profileId), eq(HH_RESUME_ID), eq("Java-разработчик"), eq(20));
        order.verify(repository).snapshotLetterSettings(runId, AiLetterSettings.defaults(), "Синтетический текст резюме");
        order.verify(repository).add(runId, accountId, vacancyId, HH_VACANCY_ID, "Java-разработчик",
                "Синтетическая компания", SOURCE_URL, 0, 0, false);
        order.verify(repository).add(runId, accountId, secondVacancyId, "87654321", "Java-разработчик",
                "Синтетическая компания", SOURCE_URL, 0, 1, false);
    }

    @Test
    void repeatedRequestWithIdenticalParametersReturnsExistingRunWithoutCreatingAnother() {
        var existing = run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)));
        when(repository.find(runId)).thenReturn(Optional.of(existing));

        assertSame(existing, service.start(start(List.of(vacancyId))));

        verify(repository, never()).create(any(), any(), any(), anyString(), anyString(), anyInt());
        verify(repository, never()).busy();
        verify(repository, never()).add(any(), any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void repeatedRequestWithAnotherQueueIsRejected() {
        var existing = run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)));
        when(repository.find(runId)).thenReturn(Optional.of(existing));

        assertConflict(() -> service.start(start(List.of(vacancyId, secondVacancyId))));
        verify(repository, never()).create(any(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void repeatedRequestWithAnotherLetterIsRejected() {
        var existing = run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)));
        when(repository.find(runId)).thenReturn(Optional.of(existing));

        assertConflict(() -> service.start(start(List.of(vacancyId), 20, "Другое письмо", AiLetterSettings.defaults())));
    }

    @Test
    void repeatedRequestWithAnotherAiSnapshotIsRejected() {
        var existing = run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)));
        when(repository.find(runId)).thenReturn(Optional.of(existing));

        assertConflict(() -> service.start(start(List.of(vacancyId), 20, "Обычное письмо",
                new AiLetterSettings(true, AI_TEMPLATE, 1500))));
    }

    @ParameterizedTest
    @EnumSource(ChangedSnapshotField.class)
    void repeatedRequestRejectsEveryOtherChangedRunParameter(ChangedSnapshotField changedField) {
        var existing = run(RunStatus.RUNNING, List.of(item("QUEUED", null, null)));
        when(repository.find(runId)).thenReturn(Optional.of(existing));
        UUID requestedResumeId = changedField == ChangedSnapshotField.RESUME
                ? UUID.fromString("20000000-0000-0000-0000-000000000099") : resumeId;
        int dailyLimit = changedField == ChangedSnapshotField.DAILY_LIMIT ? 19 : 20;
        int pauseFrom = changedField == ChangedSnapshotField.PAUSE_FROM ? 11 : 10;
        int pauseTo = changedField == ChangedSnapshotField.PAUSE_TO
                || changedField == ChangedSnapshotField.PAUSE_FROM ? 11 : 10;
        boolean skipQuestionnaires = changedField != ChangedSnapshotField.SKIP_QUESTIONNAIRES;
        Start changed = new Start(runId, requestedResumeId, List.of(vacancyId), dailyLimit,
                pauseFrom, pauseTo, "Обычное письмо", skipQuestionnaires, AiLetterSettings.defaults());

        assertConflict(() -> service.start(changed));

        verify(repository, never()).create(any(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void anotherUnfinishedRunBlocksTheNewOne() {
        when(repository.find(runId)).thenReturn(Optional.empty());
        when(repository.busy()).thenReturn(true);

        assertConflict(() -> service.start(start(List.of(vacancyId))));
        verify(repository, never()).create(any(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void emptyQueueIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());

        assertConflict(() -> service.start(start(List.of())));
    }

    @Test
    void duplicateVacancyInQueueIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());

        assertConflict(() -> service.start(start(List.of(vacancyId, vacancyId))));
    }

    @Test
    void queueLongerThanRequestedDailyLimitIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());

        assertConflict(() -> service.start(start(List.of(vacancyId, secondVacancyId), 1, "Обычное письмо",
                AiLetterSettings.defaults())));
    }

    @Test
    void invertedPauseRangeIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());

        assertConflict(() -> service.start(new Start(runId, resumeId, List.of(vacancyId), 20, 90, 30,
                "Обычное письмо", true, AiLetterSettings.defaults())));
    }

    @Test
    void queueLargerThanRemainingDailyBudgetIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());
        stubResume(20, "Синтетический текст резюме");
        when(repository.attemptsToday(accountId)).thenReturn(20L);

        assertConflict(() -> service.start(start(List.of(vacancyId))));
        verify(repository, never()).create(any(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void vacancyOfAnotherResumeIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());
        stubResume(20, "Синтетический текст резюме");
        var vacancy = stubVacancy(VacancyStatus.NEW, true);
        when(vacancy.resumeId()).thenReturn(UUID.randomUUID());

        assertConflict(() -> service.start(start(List.of(vacancyId))));
        verify(repository, never()).add(any(), any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void vacancyRemovedFromTheQueueIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());
        stubResume(20, "Синтетический текст резюме");
        stubVacancy(VacancyStatus.NEW, false);

        assertConflict(() -> service.start(start(List.of(vacancyId))));
    }

    @Test
    void vacancyInTerminalStatusIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());
        stubResume(20, "Синтетический текст резюме");
        stubVacancy(VacancyStatus.ALREADY_APPLIED, true);

        assertConflict(() -> service.start(start(List.of(vacancyId))));
    }

    @Test
    void vacancyProtectedByDuplicateGuardIsRejected() {
        when(repository.find(runId)).thenReturn(Optional.empty());
        stubResume(20, "Синтетический текст резюме");
        stubVacancy(VacancyStatus.NEW, true);
        when(repository.guarded(accountId, HH_VACANCY_ID, runId)).thenReturn(true);

        assertConflict(() -> service.start(start(List.of(vacancyId))));
        verify(repository, never()).add(any(), any(), any(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void invalidAiTemplateIsRejectedBeforeTakingTheQueueLock() {
        var exception = assertThrows(ApplicationException.class, () -> service.start(start(List.of(vacancyId), 20,
                "Обычное письмо", new AiLetterSettings(true, "Шаблон без маркера", 1500))));

        assertEquals("INVALID_AI_LETTER_SETTINGS", exception.machineCode());
        verify(repository, never()).lock();
    }

    @Test
    void fallbackLetterWithGenerationMarkerIsRejectedBeforeTakingTheQueueLock() {
        var exception = assertThrows(ApplicationException.class, () -> service.start(start(List.of(vacancyId), 20,
                "Здравствуйте! #AI_CHANGE#", AiLetterSettings.defaults())));

        assertEquals("INVALID_AI_LETTER_SETTINGS", exception.machineCode());
        verify(repository, never()).lock();
    }

    private void assertConflict(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(ApplicationException.class, executable);
        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
    }

    private enum ChangedSnapshotField {
        RESUME, DAILY_LIMIT, PAUSE_FROM, PAUSE_TO, SKIP_QUESTIONNAIRES
    }
}
