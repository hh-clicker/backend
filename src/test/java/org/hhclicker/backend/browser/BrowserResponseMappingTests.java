package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.browser.dto.BrowserPageResponse;
import org.hhclicker.backend.browser.dto.BrowserProfileResponse;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.service.BrowserPageData;
import org.hhclicker.backend.browser.service.BrowserRuntimeSnapshot;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Фабрики ответов страницы «Браузер»: что берётся из домена, а что зашито в код.
 */
class BrowserResponseMappingTests {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-04T09:00:00Z");

    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID otherProfileId = UUID.randomUUID();

    private final BrowserProfile profile = new BrowserProfile(profileId, accountId, CREATED_AT, UPDATED_AT);

    private BrowserRuntimeSnapshot runtime(String status, boolean browserOpen, UUID activeProfileId) {
        return new BrowserRuntimeSnapshot(status, "1.2.3", browserOpen, activeProfileId);
    }

    // --- BrowserPageResponse ---------------------------------------------------------------

    @Test
    void agentBlockCombinesRuntimeFactsWithHardcodedEnvironmentDescription() {
        BrowserPageResponse response = BrowserPageResponse.from(new BrowserPageData(
                runtime("READY", true, profileId), AuthorizationStatus.AUTHORIZED, profile));

        assertEquals("READY", response.agent().status());
        assertEquals("1.2.3", response.agent().version());
        assertEquals("AUTHORIZED", response.agent().authorizationStatus());
        assertEquals("HTTP polling", response.agent().endpoint());
        assertEquals("Локальный компьютер", response.agent().host());
        assertEquals("Google Chrome · Playwright", response.agent().browserVersion());
    }

    @ParameterizedTest
    @EnumSource(AuthorizationStatus.class)
    void authorizationStatusIsExposedAsTheEnumName(AuthorizationStatus status) {
        BrowserPageResponse response = BrowserPageResponse.from(
                new BrowserPageData(runtime("READY", false, null), status, null));

        assertEquals(status.name(), response.agent().authorizationStatus());
    }

    @Test
    void unknownAgentFactsAreForwardedAsTheyAreWithoutDefaults() {
        BrowserPageResponse response = BrowserPageResponse.from(new BrowserPageData(
                new BrowserRuntimeSnapshot(null, null, false, null), AuthorizationStatus.REQUIRED, null));

        assertNull(response.agent().status());
        assertNull(response.agent().version());
        assertEquals("HTTP polling", response.agent().endpoint(), "окружение остаётся зашитым");
    }

    @Test
    void accountWithoutABrowserProfileGetsAnEmptyProfileBlock() {
        BrowserPageResponse response = BrowserPageResponse.from(
                new BrowserPageData(runtime("READY", false, null), AuthorizationStatus.UNKNOWN, null));

        assertNull(response.profile());
    }

    @Test
    void existingProfileIsMappedIntoTheSameResponseAsTheProfileFactory() {
        BrowserRuntimeSnapshot snapshot = runtime("BUSY", true, profileId);

        BrowserPageResponse response = BrowserPageResponse.from(
                new BrowserPageData(snapshot, AuthorizationStatus.AUTHORIZED, profile));

        assertEquals(BrowserProfileResponse.from(profile, snapshot), response.profile());
    }

    /**
     * Фиксирует хрупкость фабрики: отсутствующий статус авторизации ломает построение ответа.
     */
    @Test
    void missingAuthorizationStatusIsNotToleratedByTheFactory() {
        BrowserPageData data = new BrowserPageData(runtime("READY", false, null), null, null);

        assertThrows(NullPointerException.class, () -> BrowserPageResponse.from(data));
    }

    // --- BrowserProfileResponse -------------------------------------------------------------

    @Test
    void profileIsRunningOnlyWhenTheAgentKeepsThatVeryProfileOpen() {
        assertEquals("RUNNING", BrowserProfileResponse.from(profile, runtime("READY", true, profileId))
                .processStatus());
    }

    @Test
    void closedBrowserMakesTheProfileStopped() {
        assertEquals("STOPPED", BrowserProfileResponse.from(profile, runtime("READY", false, profileId))
                .processStatus());
    }

    @Test
    void browserOfAnotherProfileMakesTheProfileStopped() {
        assertEquals("STOPPED", BrowserProfileResponse.from(profile, runtime("READY", true, otherProfileId))
                .processStatus());
        assertEquals("STOPPED", BrowserProfileResponse.from(profile, runtime("READY", true, null))
                .processStatus());
    }

    @Test
    void identifiersAndCreationTimeComeFromTheStoredProfile() {
        BrowserProfileResponse response = BrowserProfileResponse.from(profile, runtime("READY", true, profileId));

        assertEquals(profileId, response.id());
        assertEquals(accountId, response.accountId());
        assertEquals(CREATED_AT, response.createdAt());
        assertEquals("profiles/" + profileId, response.profileDirectory());
    }

    /**
     * Теги и время последнего запуска пока не хранятся, ответ отдаёт пустые значения.
     */
    @Test
    void tagsAreEmptyAndImmutableWhileLastStartAtIsNotTrackedYet() {
        BrowserProfileResponse response = BrowserProfileResponse.from(profile, runtime("READY", true, profileId));

        assertTrue(response.tags().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> response.tags().add("основной"));
        assertNull(response.lastStartedAt());
    }
}
