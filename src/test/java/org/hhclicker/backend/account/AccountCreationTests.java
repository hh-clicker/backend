package org.hhclicker.backend.account;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.hhclicker.backend.account.exception.HhAccountAlreadyExistsException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.CreateAccountCommand;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Создание HH-аккаунта: нормализация, границы, серверное время и конфликты по hhUserId.
 */
class AccountCreationTests extends AccountServiceTestSupport {

    private CreateAccountCommand command(String name, String hhUserId) {
        return new CreateAccountCommand(name, hhUserId);
    }

    @Test
    void createTrimsInputAndStampsTheAccountWithServerTime() {
        HhAccount created = service.create(command("  Основной аккаунт  ", "  " + HH_USER_ID + "  "));

        HhAccount inserted = captureInsert();
        assertEquals("Основной аккаунт", inserted.name());
        assertEquals(HH_USER_ID, inserted.hhUserId());
        assertEquals(AuthorizationStatus.UNKNOWN, inserted.authorizationStatus(),
                "новый аккаунт ещё не проверялся Browser Agent");
        assertEquals(NOW, inserted.createdAt());
        assertEquals(NOW, inserted.updatedAt());
        assertNotNull(inserted.id());
        assertSame(inserted, created, "сервис возвращает ровно то, что записал");
    }

    @Test
    void everyNewAccountGetsItsOwnIdentifier() {
        service.create(command("Первый", null));
        service.create(command("Второй", null));

        ArgumentCaptor<HhAccount> captor = ArgumentCaptor.forClass(HhAccount.class);
        verify(repository, times(2)).insert(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).id(), captor.getAllValues().get(1).id());
    }

    @Test
    void blankHhUserIdBecomesNullAndSkipsTheUniquenessCheck() {
        service.create(command("Основной аккаунт", "   "));

        assertNull(captureInsert().hhUserId());
        verify(repository, never()).findByHhUserId(anyString());
    }

    @Test
    void missingHhUserIdIsAcceptedWithoutAUniquenessCheck() {
        service.create(command("Основной аккаунт", null));

        assertNull(captureInsert().hhUserId());
        verify(repository, never()).findByHhUserId(anyString());
    }

    @Test
    void blankNameIsRejected() {
        assertInvalidAccountData(() -> service.create(command(null, HH_USER_ID)));
        assertInvalidAccountData(() -> service.create(command("   ", HH_USER_ID)));

        verify(repository, never()).insert(any());
    }

    @Test
    void nameLengthBoundIsInclusive() {
        service.create(command("и".repeat(120), null));
        assertEquals(120, captureInsert().name().length());

        assertInvalidAccountData(() -> service.create(command("и".repeat(121), null)));
    }

    @Test
    void hhUserIdLengthBoundIsInclusiveAndMeasuredAfterTrimming() {
        String maximal = "u".repeat(100);
        service.create(command("Основной аккаунт", "  " + maximal + "  "));
        assertEquals(maximal, captureInsert().hhUserId(),
                "длина проверяется по обрезанному значению, а не по исходной строке");

        assertInvalidAccountData(() -> service.create(command("Основной аккаунт", "u".repeat(101))));
    }

    @Test
    void nameIsValidatedBeforeHhUserId() {
        assertInvalidAccountData(() -> service.create(command("   ", "u".repeat(101))));

        verify(repository, never()).findByHhUserId(anyString());
    }

    @Test
    void hhUserIdTakenByAnotherAccountIsDetectedBeforeInsert() {
        when(repository.findByHhUserId(HH_USER_ID)).thenReturn(Optional.of(account()));

        assertAlreadyExists(() -> service.create(command("Ещё один аккаунт", HH_USER_ID)));

        verify(repository, never()).insert(any());
    }

    @Test
    void conflictRaisedByTheDatabaseDuringInsertIsTranslatedAndKeepsTheCause() {
        when(repository.findByHhUserId(HH_USER_ID)).thenReturn(Optional.empty());
        var cause = new DuplicateKeyException("uq_hh_account_hh_user_id");
        doThrow(cause).when(repository).insert(any());

        var exception = assertThrows(HhAccountAlreadyExistsException.class,
                () -> service.create(command("Основной аккаунт", HH_USER_ID)));

        assertSame(cause, exception.getCause(),
                "гонка двух параллельных созданий должна доходить до пользователя как конфликт");
        assertEquals("HH_ACCOUNT_ALREADY_EXISTS", exception.machineCode());
    }
}
