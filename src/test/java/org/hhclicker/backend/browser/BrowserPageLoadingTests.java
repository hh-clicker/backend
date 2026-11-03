package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.browser.service.BrowserPageData;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Страница «Браузер»: снимок агента, статус авторизации аккаунта и, возможно, профиль.
 */
class BrowserPageLoadingTests extends BrowserServiceTestSupport {

    @Test
    void accountWithoutABrowserProfileIsNotAnError() {
        accountExists(AuthorizationStatus.UNKNOWN);
        noProfileExists();

        BrowserPageData page = service.loadPage(accountId);

        assertNull(page.profile(), "отсутствие профиля — нормальное состояние страницы");
        assertEquals(AuthorizationStatus.UNKNOWN, page.authorizationStatus());
        assertEquals("READY", page.runtime().agentStatus());
    }

    @Test
    void accountWithAProfileExposesItTogetherWithTheRuntimeSnapshot() {
        accountExists(AuthorizationStatus.AUTHORIZED);
        profileExists();

        BrowserPageData page = service.loadPage(accountId);

        assertSame(profile, page.profile());
        assertEquals(AGENT_VERSION, page.runtime().agentVersion());
        assertTrue(page.runtime().browserOpen());
        assertEquals(profileId, page.runtime().activeProfileId());
    }

    @ParameterizedTest
    @EnumSource(AuthorizationStatus.class)
    void authorizationStatusIsTakenFromTheAccount(AuthorizationStatus status) {
        accountExists(status);
        noProfileExists();

        assertEquals(status, service.loadPage(accountId).authorizationStatus());
    }

    @Test
    void offlineAgentIsReportedAsIsWithoutAnError() {
        accountExists(AuthorizationStatus.REQUIRED);
        profileExists();
        runtimeReports("OFFLINE", false, null);

        BrowserPageData page = service.loadPage(accountId);

        assertEquals("OFFLINE", page.runtime().agentStatus());
        assertSame(profile, page.profile(), "профиль остаётся виден и при отключённом агенте");
    }

    @Test
    void missingAccountIsReportedBeforeAnythingElseIsRead() {
        when(accounts.findById(accountId)).thenThrow(new AccountNotFoundException(accountId));

        assertThrows(AccountNotFoundException.class, () -> service.loadPage(accountId));

        verifyNoInteractions(repository, runtime);
    }

    @Test
    void pageIsBuiltFromExactlyOneRuntimeSnapshotAndOneProfileLookup() {
        accountExists(AuthorizationStatus.AUTHORIZED);
        profileExists();

        service.loadPage(accountId);

        verify(runtime).getSnapshot();
        verify(repository).findByAccountId(accountId);
        verify(repository, never()).findById(any());
        verify(runtime, never()).open(any());
    }
}
