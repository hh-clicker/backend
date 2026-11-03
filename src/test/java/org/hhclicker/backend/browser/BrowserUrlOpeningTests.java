package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Открытие адреса в запущенном профиле: предусловия и проверка ссылки на hh.ru.
 */
class BrowserUrlOpeningTests extends BrowserServiceTestSupport {

    private String forwardedUrl(String sourceUrl) {
        profileExists();
        when(runtime.openUrl(any(), anyString())).thenReturn(commandId);

        assertEquals(commandId, service.openUrl(accountId, sourceUrl));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(runtime).openUrl(org.mockito.ArgumentMatchers.eq(profile), captor.capture());
        return captor.getValue();
    }

    // --- предусловия -----------------------------------------------------------------------

    @Test
    void unavailableAgentStopsTheRequestBeforeTheProfileIsLookedUp() {
        runtimeReports("OFFLINE", true, profileId);

        assertRuntimeUnavailable(() -> service.openUrl(accountId, "https://hh.ru/applicant/resumes"));

        verify(repository, never()).findByAccountId(any());
        verify(runtime, never()).openUrl(any(), anyString());
    }

    @Test
    void accountWithoutAProfileCannotOpenAnything() {
        noProfileExists();

        assertProfileNotFound(() -> service.openUrl(accountId, "https://hh.ru/applicant/resumes"));

        verify(runtime, never()).openUrl(any(), anyString());
    }

    @Test
    void closedBrowserIsReportedAsNotRunning() {
        profileExists();
        runtimeReports("READY", false, profileId);

        assertBrowserNotRunning(() -> service.openUrl(accountId, "https://hh.ru/applicant/resumes"));

        verify(runtime, never()).openUrl(any(), anyString());
    }

    @Test
    void browserOfAnotherProfileIsReportedAsNotRunning() {
        profileExists();
        runtimeReports("READY", true, otherProfileId);

        assertBrowserNotRunning(() -> service.openUrl(accountId, "https://hh.ru/applicant/resumes"));
    }

    @Test
    void openBrowserWithoutAnActiveProfileIsReportedAsNotRunning() {
        profileExists();
        runtimeReports("READY", true, null);

        assertBrowserNotRunning(() -> service.openUrl(accountId, "https://hh.ru/applicant/resumes"));
    }

    /**
     * Фиксирует порядок проверок: ссылка разбирается последней, поэтому при закрытом браузере
     * пользователь узнает о браузере, а не о неверной ссылке.
     */
    @Test
    void linkIsValidatedOnlyAfterEveryRuntimePreconditionPassed() {
        profileExists();
        runtimeReports("READY", false, null);

        assertBrowserNotRunning(() -> service.openUrl(accountId, "ftp://example.com"));

        noProfileExists();
        runtimeReports("READY", true, profileId);
        assertProfileNotFound(() -> service.openUrl(accountId, "ftp://example.com"));

        runtimeReports("OFFLINE", true, profileId);
        assertRuntimeUnavailable(() -> service.openUrl(accountId, "ftp://example.com"));
    }

    // --- принятые ссылки -------------------------------------------------------------------

    @Test
    void plainHhLinkIsForwardedToTheGateway() {
        assertEquals("https://hh.ru/applicant/resumes", forwardedUrl("https://hh.ru/applicant/resumes"));
    }

    @Test
    void regionalSubdomainIsAccepted() {
        assertEquals("https://spb.hh.ru/search/vacancy", forwardedUrl("https://spb.hh.ru/search/vacancy"));
    }

    @Test
    void queryAndFragmentSurviveUntouched() {
        assertEquals("https://hh.ru/search/vacancy?text=java#results",
                forwardedUrl("https://hh.ru/search/vacancy?text=java#results"));
    }

    @Test
    void surroundingWhitespaceIsTrimmedBeforeTheLinkIsForwarded() {
        assertEquals("https://hh.ru/applicant/resumes", forwardedUrl("   https://hh.ru/applicant/resumes   "));
    }

    /**
     * Фиксирует отсутствие канонизации: регистр проверяется, но в шлюз уходит исходная строка,
     * поэтому эквивалентные ссылки сохраняют разное текстовое представление.
     */
    @Test
    void caseOfSchemeAndHostIsAcceptedButNotNormalized() {
        assertEquals("HTTPS://HH.RU/applicant/resumes", forwardedUrl("HTTPS://HH.RU/applicant/resumes"));
    }

    /**
     * Логин в ссылке допускается, если настоящий host всё ещё принадлежит hh.ru.
     */
    @Test
    void userInfoIsAcceptedWhenTheHostItselfBelongsToHh() {
        assertEquals("https://user@hh.ru/applicant/resumes",
                forwardedUrl("https://user@hh.ru/applicant/resumes"));
    }

    // --- отклонённые ссылки ----------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "https://hh .ru/applicant",
            "hh.ru/applicant/resumes",
            "/applicant/resumes",
            "https:///applicant/resumes",
            "http://hh.ru/applicant/resumes",
            "https://example.com/applicant",
            "https://hh.ru.evil.example/applicant",
            "https://evil-hh.ru/applicant",
            "https://hh.ru@evil.example/applicant",
            "https://xn--h-8sb.ru/applicant",
            "https://хh.ru/applicant",
            "ftp://hh.ru/applicant",
            "javascript:alert(1)"
    })
    void linkThatDoesNotLeadToHhOverHttpsIsRejected(String sourceUrl) {
        profileExists();

        assertInvalidUrl(() -> service.openUrl(accountId, sourceUrl));

        verify(runtime, never()).openUrl(any(), anyString());
    }
}
