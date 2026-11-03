package org.hhclicker.backend.application;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationDryRunTests extends ApplicationServiceTestSupport {

    private Run dryRun(String itemStatus) {
        return new Run(runId, 1, accountId, resumeId, profileId, HH_RESUME_ID, "Java-разработчик",
                RunStatus.RUNNING, NOW, null, 1, 20, 10, 10, "", false,
                List.of(item(itemStatus, null, null)), AiLetterSettings.defaults(), null, false, RunMode.DRY_RUN);
    }

    @Test
    void startIgnoresApplicationDailyBudgetAndDuplicateGuard() {
        stubResume(20, "Резюме");
        stubVacancy(VacancyStatus.NEW, true);
        when(repository.find(runId)).thenReturn(Optional.empty(), Optional.of(dryRun("QUEUED")));
        Start request = new Start(runId, resumeId, List.of(vacancyId), 1, 10, 10,
                "", false, AiLetterSettings.defaults(), RunMode.DRY_RUN);

        assertEquals(RunMode.DRY_RUN, service.start(request).mode());

        verify(repository).create(eq(request), eq(accountId), eq(profileId), eq(HH_RESUME_ID),
                eq("Java-разработчик"), eq(20));
        verify(repository, never()).guarded(any(), anyString(), any());
        verify(repository, never()).attemptsToday(any());
        verify(repository).add(eq(runId), eq(accountId), eq(vacancyId), eq(HH_VACANCY_ID),
                anyString(), anyString(), anyString(), anyInt(), eq(0), anyBoolean());
    }

    @Test
    void dryRunNeverPermitsSubmissionOrLetterPreparation() {
        Item item = item("IN_PROGRESS", null, null);
        stub(item, dryRun("IN_PROGRESS"));

        assertFalse(service.permit(itemId));
        assertFalse(service.prepareLetter(itemId, new PreparedLetter("Письмо", "STATIC", null, null)));

        verify(repository, never()).permit(any());
        verify(repository, never()).prepareLetter(any(), any());
    }

    @Test
    void textIsSavedWithoutChangingVacancyStatusOrSubmissionGuard() {
        Item item = item("IN_PROGRESS", null, null);
        stub(item, dryRun("IN_PROGRESS"));

        service.complete(itemId, Outcome.TEXT_LOADED, null, "Требуется Java и Spring");

        verify(vacancies).saveText(vacancyId, "Требуется Java и Spring");
        verify(repository).finish(itemId, Outcome.TEXT_LOADED, false);
        verify(vacancies, never()).markApplied(any());
        verify(vacancies, never()).markQuestionnaire(any());
        verify(repository, never()).permit(any());
    }

    @Test
    void realApplicationCannotAcceptDryRunOutcome() {
        prepare("IN_PROGRESS", null, null, RunStatus.RUNNING);

        assertThrows(ApplicationException.class, () -> service.complete(itemId, Outcome.TEXT_LOADED, null, "Текст"));

        verify(vacancies, never()).saveText(any(), anyString());
        verify(repository, never()).finish(any(), any(), anyBoolean());
    }

    @Test
    void archivedVacancyInDryRunIsClosedWithoutSavingItsDescription() {
        Item item = item("IN_PROGRESS", null, null);
        stub(item, dryRun("IN_PROGRESS"));

        service.complete(itemId, Outcome.VACANCY_ARCHIVED, null, "Archive description must not be stored");

        verify(repository).finish(itemId, Outcome.VACANCY_ARCHIVED, false);
        verify(vacancySelection).closeFromHh(vacancyId);
        verify(vacancies, never()).saveText(any(), any());
        verify(repository).recordTechnicalFailure(runId, false);
    }
}
