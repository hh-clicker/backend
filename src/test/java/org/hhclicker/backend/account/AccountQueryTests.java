package org.hhclicker.backend.account;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Чтение HH-аккаунтов: сервис ничего не переупорядочивает и не подменяет.
 */
class AccountQueryTests extends AccountServiceTestSupport {

    @Test
    void listIsReturnedExactlyAsTheRepositoryProvidedIt() {
        HhAccount first = account();
        HhAccount second = account(UUID.randomUUID(), "Запасной аккаунт", null, AuthorizationStatus.REQUIRED);
        List<HhAccount> stored = List.of(first, second);
        when(repository.findAll()).thenReturn(stored);

        var accounts = service.findAll();

        assertSame(stored, accounts, "сервис не должен копировать или пересобирать список");
        assertEquals(List.of(first, second), accounts, "порядок репозитория обязан сохраняться");
    }

    @Test
    void emptyRepositoryProducesAnEmptyList() {
        when(repository.findAll()).thenReturn(List.of());

        assertTrue(service.findAll().isEmpty());
    }

    @Test
    void storedAccountIsReturnedAsIs() {
        HhAccount stored = account();
        when(repository.findById(accountId)).thenReturn(Optional.of(stored));

        assertSame(stored, service.findById(accountId));
    }

    @Test
    void missingAccountIsReportedAsNotFound() {
        when(repository.findById(accountId)).thenReturn(Optional.empty());

        var exception = assertThrows(AccountNotFoundException.class, () -> service.findById(accountId));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("ACCOUNT_NOT_FOUND", exception.machineCode());
    }
}
