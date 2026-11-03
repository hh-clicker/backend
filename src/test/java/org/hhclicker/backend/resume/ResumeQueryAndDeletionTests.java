package org.hhclicker.backend.resume;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.resume.exception.ResumeHasDependentDataException;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Чтение резюме и удаление: сборка ResumeProfile, отсутствующие записи, зависимые данные.
 */
class ResumeQueryAndDeletionTests extends ResumeServiceTestSupport {

    private final UUID secondResumeId = UUID.randomUUID();

    @Test
    void resumesOfOneAccountKeepRepositoryOrderAndResolveAccountNameOnce() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        HhResume first = resume();
        HhResume second = resume(secondResumeId, accountId, OTHER_HASH, "Backend-инженер", ResumeAutomationSettings.defaults());
        when(repository.findAllByAccountId(accountId)).thenReturn(List.of(first, second));

        var profiles = service.findAll(accountId);

        assertEquals(List.of(resumeId, secondResumeId), profiles.stream().map(p -> p.resume().id()).toList());
        assertEquals(List.of("Основной аккаунт", "Основной аккаунт"), profiles.stream().map(p -> p.accountName()).toList());
        assertSame(statistics, profiles.getFirst().statistics());
        verify(accounts, times(1)).findById(accountId);
        verify(repository, never()).findAll();
    }

    @Test
    void resumesOfAllAccountsResolveTheirOwnAccountName() {
        UUID otherAccountId = UUID.randomUUID();
        stubAccount("Основной аккаунт");
        stubAccount(otherAccountId, "Запасной аккаунт");
        stubStatistics();
        when(repository.findAll()).thenReturn(List.of(
                resume(),
                resume(secondResumeId, otherAccountId, OTHER_HASH, "Backend-инженер", ResumeAutomationSettings.defaults())));

        var profiles = service.findAll(null);

        assertEquals(List.of("Основной аккаунт", "Запасной аккаунт"), profiles.stream().map(p -> p.accountName()).toList());
        verify(repository, never()).findAllByAccountId(any());
    }

    @Test
    void unknownAccountIsReportedBeforeReadingResumes() {
        when(accounts.findById(accountId)).thenThrow(new AccountNotFoundException(accountId));

        assertThrows(AccountNotFoundException.class, () -> service.findAll(accountId));

        verifyNoInteractions(repository);
    }

    @Test
    void accountWithoutResumesReturnsEmptyList() {
        stubAccount("Основной аккаунт");
        when(repository.findAllByAccountId(accountId)).thenReturn(List.of());

        assertTrue(service.findAll(accountId).isEmpty());
    }

    @Test
    void singleResumeIsReturnedWithAccountNameAndStatistics() {
        stubAccount("Основной аккаунт");
        stubStatistics();
        HhResume stored = resume();
        when(repository.findById(resumeId)).thenReturn(Optional.of(stored));

        var profile = service.findById(resumeId);

        assertSame(stored, profile.resume());
        assertEquals("Основной аккаунт", profile.accountName());
        assertSame(statistics, profile.statistics());
        verify(statisticsProvider).get(resumeId);
    }

    @Test
    void missingResumeIsReportedAsNotFound() {
        when(repository.findById(resumeId)).thenReturn(Optional.empty());

        var exception = assertThrows(ResumeNotFoundException.class, () -> service.findById(resumeId));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("RESUME_NOT_FOUND", exception.machineCode());
        verifyNoInteractions(accounts, statisticsProvider);
    }

    @Test
    void deletingAnExistingResumeSucceeds() {
        when(repository.deleteById(resumeId)).thenReturn(true);

        assertDoesNotThrow(() -> service.delete(resumeId));

        verify(repository).deleteById(resumeId);
    }

    @Test
    void deletingAMissingResumeIsReportedAsNotFound() {
        when(repository.deleteById(resumeId)).thenReturn(false);

        assertThrows(ResumeNotFoundException.class, () -> service.delete(resumeId));
    }

    @Test
    void dependentDataTurnsDeletionIntoAConflictWithThePreservedCause() {
        var cause = new DataIntegrityViolationException("fk_vacancy_resume");
        when(repository.deleteById(resumeId)).thenThrow(cause);

        var exception = assertThrows(ResumeHasDependentDataException.class, () -> service.delete(resumeId));

        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("RESUME_HAS_DEPENDENT_DATA", exception.machineCode());
        assertSame(cause, exception.getCause());
    }
}
