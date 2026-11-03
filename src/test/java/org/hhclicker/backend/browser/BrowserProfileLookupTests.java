package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.hhclicker.backend.browser.exception.BrowserProfileNotFoundException;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.repository.BrowserProfileRepository;
import org.hhclicker.backend.browser.service.BrowserProfileLookup;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Чтение профиля по аккаунту без зависимости от Browser Agent.
 */
class BrowserProfileLookupTests {

    private static final Instant EARLIER = Instant.parse("2026-09-01T08:00:00Z");

    private final BrowserProfileRepository repository = mock(BrowserProfileRepository.class);
    private final BrowserProfileLookup lookup = new BrowserProfileLookup(repository);

    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @Test
    void profileOfTheAccountIsReturnedAsIs() {
        BrowserProfile stored = new BrowserProfile(profileId, accountId, EARLIER, EARLIER);
        when(repository.findByAccountId(accountId)).thenReturn(Optional.of(stored));

        assertSame(stored, lookup.forAccount(accountId));

        verify(repository).findByAccountId(accountId);
    }

    /**
     * Фиксирует текущее сообщение: при отсутствии профиля в исключение попадает идентификатор
     * аккаунта, хотя текст называет его идентификатором профиля браузера.
     */
    @Test
    void missingProfileIsReportedWithTheAccountIdentifierInsideTheMessage() {
        when(repository.findByAccountId(accountId)).thenReturn(Optional.empty());

        var exception = assertThrows(BrowserProfileNotFoundException.class, () -> lookup.forAccount(accountId));

        assertEquals(ApplicationErrorCategory.NOT_FOUND, exception.category());
        assertEquals("BROWSER_PROFILE_NOT_FOUND", exception.machineCode());
        assertTrue(exception.safeMessage().contains(accountId.toString()),
                "в сообщении оказывается id аккаунта, а не id профиля: " + exception.safeMessage());
    }
}
