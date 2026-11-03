package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.browser.exception.BrowserProfileAlreadyExistsException;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Создание браузерного профиля: один профиль на HH-аккаунт и защита от гонки.
 */
class BrowserProfileCreationTests extends BrowserServiceTestSupport {

    @Test
    void profileIsCreatedForAnExistingAccountWithServerTimestamps() {
        accountExists(AuthorizationStatus.UNKNOWN);
        noProfileExists();

        BrowserProfile created = service.create(accountId);

        ArgumentCaptor<BrowserProfile> captor = ArgumentCaptor.forClass(BrowserProfile.class);
        verify(repository).insert(captor.capture());
        BrowserProfile inserted = captor.getValue();
        assertNotNull(inserted.id());
        assertEquals(accountId, inserted.accountId());
        assertEquals(NOW, inserted.createdAt());
        assertEquals(NOW, inserted.updatedAt());
        assertSame(inserted, created, "сервис возвращает ровно то, что записал");
    }

    @Test
    void everyProfileGetsItsOwnIdentifier() {
        accountExists(AuthorizationStatus.UNKNOWN);
        noProfileExists();

        service.create(accountId);
        service.create(accountId);

        ArgumentCaptor<BrowserProfile> captor = ArgumentCaptor.forClass(BrowserProfile.class);
        verify(repository, times(2)).insert(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).id(), captor.getAllValues().get(1).id());
    }

    @Test
    void missingAccountIsRejectedBeforeAnyProfileIsTouched() {
        when(accounts.findById(accountId)).thenThrow(new AccountNotFoundException(accountId));

        assertThrows(AccountNotFoundException.class, () -> service.create(accountId));

        verify(repository, never()).findByAccountId(any());
        verify(repository, never()).insert(any());
    }

    @Test
    void secondProfileOfTheSameAccountIsRejectedWithoutAnInsert() {
        accountExists(AuthorizationStatus.UNKNOWN);
        profileExists();

        var exception = assertThrows(BrowserProfileAlreadyExistsException.class, () -> service.create(accountId));

        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("BROWSER_PROFILE_ALREADY_EXISTS", exception.machineCode());
        verify(repository, never()).insert(any());
    }

    @Test
    void concurrentCreationLosingTheUniqueIndexIsReportedAsTheSameConflict() {
        accountExists(AuthorizationStatus.UNKNOWN);
        noProfileExists();
        doThrow(new DuplicateKeyException("uq_browser_profile_account")).when(repository).insert(any());

        var exception = assertThrows(BrowserProfileAlreadyExistsException.class, () -> service.create(accountId));

        assertEquals("BROWSER_PROFILE_ALREADY_EXISTS", exception.machineCode());
    }

    /**
     * Фиксирует границу: конфликтом считается только нарушение уникальности, а не любая ошибка БД.
     */
    @Test
    void otherStorageFailuresAreNotDisguisedAsAConflict() {
        accountExists(AuthorizationStatus.UNKNOWN);
        noProfileExists();
        doThrow(new DataIntegrityViolationException("fk_browser_profile_account")).when(repository).insert(any());

        assertThrows(DataIntegrityViolationException.class, () -> service.create(accountId));
    }

    @Test
    void creationDoesNotDependOnTheBrowserAgentBeingAvailable() {
        accountExists(AuthorizationStatus.UNKNOWN);
        when(repository.findByAccountId(accountId)).thenReturn(Optional.empty());
        runtimeReports("OFFLINE", false, null);

        assertNotNull(service.create(accountId));

        verify(runtime, never()).getSnapshot();
    }
}
