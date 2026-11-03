package org.hhclicker.backend.browser;

import org.junit.jupiter.api.BeforeEach;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.browser.exception.BrowserNotRunningException;
import org.hhclicker.backend.browser.exception.BrowserProfileNotFoundException;
import org.hhclicker.backend.browser.exception.BrowserRuntimeUnavailableException;
import org.hhclicker.backend.browser.exception.InvalidBrowserUrlException;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.repository.BrowserProfileRepository;
import org.hhclicker.backend.browser.service.BrowserProfileService;
import org.hhclicker.backend.browser.service.BrowserRuntimeGateway;
import org.hhclicker.backend.browser.service.BrowserRuntimeSnapshot;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая обвязка unit-тестов браузерного домена.
 * <p>
 * {@code BrowserRuntimeGateway} — всегда мок: очередь команд Browser Agent проверяется в пакете
 * {@code agent}, здесь важны предусловия домена и маршрутизация в порт.
 */
abstract class BrowserServiceTestSupport {

    protected static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    protected static final Instant EARLIER = Instant.parse("2026-09-01T08:00:00Z");
    protected static final String AGENT_VERSION = "1.2.3";

    protected final BrowserProfileRepository repository = mock(BrowserProfileRepository.class);
    protected final AccountService accounts = mock(AccountService.class);
    protected final BrowserRuntimeGateway runtime = mock(BrowserRuntimeGateway.class);
    protected final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    protected final BrowserProfileService service =
            new BrowserProfileService(repository, accounts, runtime, clock);

    protected final UUID accountId = UUID.randomUUID();
    protected final UUID profileId = UUID.randomUUID();
    protected final UUID otherProfileId = UUID.randomUUID();
    protected final UUID commandId = UUID.randomUUID();

    protected final BrowserProfile profile = new BrowserProfile(profileId, accountId, EARLIER, EARLIER);

    @BeforeEach
    void agentIsReadyWithThisProfileOpen() {
        runtimeReports("READY", true, profileId);
    }

    protected void runtimeReports(String agentStatus, boolean browserOpen, UUID activeProfileId) {
        when(runtime.getSnapshot())
                .thenReturn(new BrowserRuntimeSnapshot(agentStatus, AGENT_VERSION, browserOpen, activeProfileId));
    }

    protected void profileExists() {
        when(repository.findByAccountId(accountId)).thenReturn(Optional.of(profile));
    }

    protected void noProfileExists() {
        when(repository.findByAccountId(accountId)).thenReturn(Optional.empty());
    }

    protected void accountExists(AuthorizationStatus status) {
        when(accounts.findById(accountId))
                .thenReturn(new HhAccount(accountId, "Основной аккаунт", null, status, EARLIER, EARLIER));
    }

    protected void assertRuntimeUnavailable(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(BrowserRuntimeUnavailableException.class, executable);
        assertEquals(ApplicationErrorCategory.EXTERNAL_SYSTEM, exception.category());
        assertEquals("BROWSER_AGENT_UNAVAILABLE", exception.machineCode());
    }

    protected void assertBrowserNotRunning(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(BrowserNotRunningException.class, executable);
        assertEquals(ApplicationErrorCategory.CONFLICT, exception.category());
        assertEquals("BROWSER_NOT_RUNNING", exception.machineCode());
    }

    protected void assertProfileNotFound(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(BrowserProfileNotFoundException.class, executable);
        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("BROWSER_PROFILE_NOT_FOUND", exception.machineCode());
    }

    protected void assertInvalidUrl(org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(InvalidBrowserUrlException.class, executable);
        assertEquals(ApplicationErrorCategory.VALIDATION, exception.category());
        assertEquals("INVALID_BROWSER_URL", exception.machineCode());
    }
}
