package org.hhclicker.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.exception.HhAccountAlreadyExistsException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.UpdateAccountCommand;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Изменение HH-аккаунта: неизменяемые поля, перечитывание результата и конфликты по hhUserId.
 */
class AccountUpdateTests extends AccountServiceTestSupport {

    private UpdateAccountCommand command(String name, String hhUserId) {
        return new UpdateAccountCommand(name, hhUserId);
    }

    /**
     * Первое чтение отдаёт исходную строку, второе — то, что осталось в БД после записи.
     */
    private void stubWritable(HhAccount existing, HhAccount refreshed) {
        when(repository.findById(accountId)).thenReturn(Optional.of(existing), Optional.of(refreshed));
        when(repository.update(any())).thenReturn(true);
    }

    @Test
    void updateChangesOnlyNameAndHhUserIdAndReturnsTheRereadRow() {
        HhAccount existing = account();
        HhAccount refreshed = new HhAccount(accountId, "Имя из базы", "hh-user-new",
                AuthorizationStatus.AUTHORIZED, CREATED_AT, NOW);
        stubWritable(existing, refreshed);

        HhAccount result = service.update(accountId, command("Новое имя", "hh-user-new"));

        HhAccount written = captureUpdate();
        assertEquals("Новое имя", written.name());
        assertEquals("hh-user-new", written.hhUserId());
        assertEquals(accountId, written.id());
        assertEquals(AuthorizationStatus.AUTHORIZED, written.authorizationStatus(),
                "изменение профиля не трогает состояние авторизации HH");
        assertEquals(CREATED_AT, written.createdAt());
        assertEquals(NOW, written.updatedAt());
        assertSame(refreshed, result, "результат обязан читаться из репозитория после записи");
    }

    @Test
    void valuesAreTrimmedTheSameWayAsOnCreation() {
        HhAccount existing = account();
        stubWritable(existing, existing);

        service.update(accountId, command("  Новое имя  ", "  hh-user-new  "));

        HhAccount written = captureUpdate();
        assertEquals("Новое имя", written.name());
        assertEquals("hh-user-new", written.hhUserId());
    }

    @Test
    void keepingTheOwnHhUserIdIsAllowed() {
        HhAccount existing = account();
        stubWritable(existing, existing);
        when(repository.findByHhUserId(HH_USER_ID)).thenReturn(Optional.of(existing));

        service.update(accountId, command("Новое имя", HH_USER_ID));

        assertEquals(HH_USER_ID, captureUpdate().hhUserId());
    }

    @Test
    void hhUserIdOwnedByAnotherAccountIsRejected() {
        HhAccount existing = account();
        when(repository.findById(accountId)).thenReturn(Optional.of(existing));
        when(repository.findByHhUserId(HH_USER_ID)).thenReturn(Optional.of(
                account(UUID.randomUUID(), "Чужой аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED)));

        assertAlreadyExists(() -> service.update(accountId, command("Новое имя", HH_USER_ID)));

        verify(repository, never()).update(any());
    }

    @Test
    void blankHhUserIdClearsTheLinkWithoutAUniquenessCheck() {
        HhAccount existing = account();
        stubWritable(existing, existing);

        service.update(accountId, command("Новое имя", "   "));

        assertNull(captureUpdate().hhUserId());
        verify(repository, never()).findByHhUserId(anyString());
    }

    @Test
    void nameAndHhUserIdFollowTheSameValidationRulesAsOnCreation() {
        when(repository.findById(accountId)).thenReturn(Optional.of(account()));

        assertInvalidAccountData(() -> service.update(accountId, command(null, null)));
        assertInvalidAccountData(() -> service.update(accountId, command("   ", null)));
        assertInvalidAccountData(() -> service.update(accountId, command("и".repeat(121), null)));
        assertInvalidAccountData(() -> service.update(accountId, command("Новое имя", "u".repeat(101))));

        verify(repository, never()).update(any());
    }

    @Test
    void missingAccountIsReportedBeforeTheRequestIsValidated() {
        when(repository.findById(accountId)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> service.update(accountId, command("   ", null)));

        verify(repository, never()).update(any());
    }

    @Test
    void updateThatChangedNoRowIsReportedAsNotFound() {
        when(repository.findById(accountId)).thenReturn(Optional.of(account()));
        when(repository.update(any())).thenReturn(false);

        assertThrows(AccountNotFoundException.class, () -> service.update(accountId, command("Новое имя", null)));
    }

    @Test
    void conflictRaisedByTheDatabaseDuringUpdateIsTranslatedAndKeepsTheCause() {
        when(repository.findById(accountId)).thenReturn(Optional.of(account()));
        var cause = new DuplicateKeyException("uq_hh_account_hh_user_id");
        when(repository.update(any())).thenThrow(cause);

        var exception = assertThrows(HhAccountAlreadyExistsException.class,
                () -> service.update(accountId, command("Новое имя", "hh-user-new")));

        assertSame(cause, exception.getCause());
        assertEquals("HH_ACCOUNT_ALREADY_EXISTS", exception.machineCode());
    }

    @Test
    void accountRemovedRightAfterASuccessfulUpdateIsReportedAsNotFound() {
        when(repository.findById(accountId)).thenReturn(Optional.of(account()), Optional.empty());
        when(repository.update(any())).thenReturn(true);

        assertThrows(AccountNotFoundException.class, () -> service.update(accountId, command("Новое имя", null)));

        verify(repository).update(any());
    }
}
