package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.browser.exception.BrowserProfileNotFoundException;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.repository.BrowserProfileRepository;
import org.hhclicker.backend.browser.service.BrowserAuthorizationService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Запись результата проверки авторизации: профиль переводится в аккаунт-владелец.
 */
class BrowserAuthorizationServiceTests {

    private static final Instant EARLIER = Instant.parse("2026-09-01T08:00:00Z");

    private final BrowserProfileRepository profiles = mock(BrowserProfileRepository.class);
    private final AccountService accounts = mock(AccountService.class);
    private final BrowserAuthorizationService service = new BrowserAuthorizationService(profiles, accounts);

    private final UUID profileId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private void profileExists() {
        when(profiles.findById(profileId))
                .thenReturn(Optional.of(new BrowserProfile(profileId, accountId, EARLIER, EARLIER)));
    }

    @ParameterizedTest
    @EnumSource(AuthorizationStatus.class)
    void statusIsWrittenToTheAccountThatOwnsTheProfile(AuthorizationStatus status) {
        profileExists();

        service.record(profileId, status);

        verify(profiles).findById(profileId);
        verify(accounts).updateAuthorizationStatus(accountId, status);
    }

    @Test
    void accountIdentifierIsUsedForTheUpdateInsteadOfTheProfileIdentifier() {
        profileExists();

        service.record(profileId, AuthorizationStatus.AUTHORIZED);

        verify(accounts).updateAuthorizationStatus(accountId, AuthorizationStatus.AUTHORIZED);
        verify(accounts, never()).updateAuthorizationStatus(
                org.mockito.ArgumentMatchers.eq(profileId), any());
    }

    @Test
    void unknownProfileIsReportedAndLeavesEveryAccountUntouched() {
        when(profiles.findById(profileId)).thenReturn(Optional.empty());

        var exception = assertThrows(BrowserProfileNotFoundException.class,
                () -> service.record(profileId, AuthorizationStatus.AUTHORIZED));

        assertEquals("BROWSER_PROFILE_NOT_FOUND", exception.machineCode());
        verifyNoInteractions(accounts);
    }

    @Test
    void failureOfTheAccountModuleIsNotSwallowed() {
        profileExists();
        doThrow(new AccountNotFoundException(accountId))
                .when(accounts).updateAuthorizationStatus(accountId, AuthorizationStatus.REQUIRED);

        assertThrows(AccountNotFoundException.class,
                () -> service.record(profileId, AuthorizationStatus.REQUIRED));
    }

    /**
     * Фиксирует границу: сервис не защищается от {@code null}, значение уходит в account как есть.
     */
    @Test
    void missingStatusIsForwardedWithoutAnyLocalValidation() {
        profileExists();

        service.record(profileId, null);

        verify(accounts).updateAuthorizationStatus(accountId, null);
    }
}
