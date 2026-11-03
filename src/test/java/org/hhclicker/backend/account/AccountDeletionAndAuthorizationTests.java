package org.hhclicker.backend.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.hhclicker.backend.account.exception.AccountHasDependentDataException;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Удаление аккаунта и запись результата проверки авторизации HH.
 */
class AccountDeletionAndAuthorizationTests extends AccountServiceTestSupport {

    @Test
    void deletingAnExistingAccountSucceeds() {
        when(repository.deleteById(accountId)).thenReturn(true);

        assertDoesNotThrow(() -> service.delete(accountId));

        verify(repository).deleteById(accountId);
    }

    @Test
    void deletingAMissingAccountIsReportedAsNotFound() {
        when(repository.deleteById(accountId)).thenReturn(false);

        var exception = assertThrows(AccountNotFoundException.class, () -> service.delete(accountId));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("ACCOUNT_NOT_FOUND", exception.machineCode());
    }

    @Test
    void dependentDataTurnsDeletionIntoAConflictWithThePreservedCause() {
        var cause = new DataIntegrityViolationException("fk_hh_resume_account");
        when(repository.deleteById(accountId)).thenThrow(cause);

        var exception = assertThrows(AccountHasDependentDataException.class, () -> service.delete(accountId));

        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("ACCOUNT_HAS_DEPENDENT_DATA", exception.machineCode());
        assertSame(cause, exception.getCause());
    }

    /**
     * Все три значения реально приходят от адаптера Browser Agent: AUTHORIZED после подтверждённого
     * входа, REQUIRED при потере сессии и UNKNOWN на время выполнения команды.
     */
    @ParameterizedTest
    @EnumSource(AuthorizationStatus.class)
    void authorizationResultIsStoredWithTheServerClock(AuthorizationStatus status) {
        when(repository.updateAuthorizationStatus(accountId, status, NOW)).thenReturn(true);

        assertDoesNotThrow(() -> service.updateAuthorizationStatus(accountId, status));

        verify(repository).updateAuthorizationStatus(accountId, status, NOW);
    }

    @Test
    void authorizationResultForAMissingAccountIsReportedAsNotFound() {
        when(repository.updateAuthorizationStatus(accountId, AuthorizationStatus.AUTHORIZED, NOW)).thenReturn(false);

        var exception = assertThrows(AccountNotFoundException.class,
                () -> service.updateAuthorizationStatus(accountId, AuthorizationStatus.AUTHORIZED));

        assertEquals("ACCOUNT_NOT_FOUND", exception.machineCode());
    }
}
