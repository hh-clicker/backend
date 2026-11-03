package org.hhclicker.backend.account;

import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.repository.AccountRepository;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Общая обвязка unit-тестов {@code AccountService}.
 * <p>
 * Часы фиксированы, поэтому проверки {@code createdAt}/{@code updatedAt} детерминированы.
 * Репозиторий подменяется моком: настоящий SQL проверяется отдельно в {@code AccountRepositoryIT}.
 */
abstract class AccountServiceTestSupport {

    protected static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    protected static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    protected static final String HH_USER_ID = "hh-user-4815162342";

    protected final AccountRepository repository = mock(AccountRepository.class);
    protected final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    protected final AccountService service = new AccountService(repository, clock);

    protected final UUID accountId = UUID.randomUUID();

    protected HhAccount account() {
        return account(accountId, "Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED);
    }

    protected HhAccount account(UUID id, String name, String hhUserId, AuthorizationStatus status) {
        return new HhAccount(id, name, hhUserId, status, CREATED_AT, CREATED_AT);
    }

    protected HhAccount captureInsert() {
        ArgumentCaptor<HhAccount> captor = ArgumentCaptor.forClass(HhAccount.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }

    protected HhAccount captureUpdate() {
        ArgumentCaptor<HhAccount> captor = ArgumentCaptor.forClass(HhAccount.class);
        verify(repository).update(captor.capture());
        return captor.getValue();
    }

    protected void assertInvalidAccountData(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(org.hhclicker.backend.account.exception.InvalidAccountDataException.class,
                executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_ACCOUNT_DATA", exception.machineCode());
    }

    protected void assertAlreadyExists(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(
                org.hhclicker.backend.account.exception.HhAccountAlreadyExistsException.class, executable);
        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("HH_ACCOUNT_ALREADY_EXISTS", exception.machineCode());
    }
}
