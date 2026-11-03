package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Операции, требующие запущенный профиль: проверка входа, чтение карточек резюме и текста резюме.
 * Общие предусловия проверяются параметризованно, маршрутизация — отдельно для каждого метода.
 */
class BrowserRunningProfileOperationsTests extends BrowserServiceTestSupport {

    private static final String HH_RESUME_ID = "abcdef0123456789abcd";

    private void invoke(String operation) {
        switch (operation) {
            case "check-authorization" -> service.checkAuthorization(accountId);
            case "discover-resumes" -> service.discoverResumes(accountId);
            case "resume-text" -> service.readResumeText(accountId, HH_RESUME_ID);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private void verifyNoCommandWasCreated() {
        verify(runtime, never()).checkAuthorization(any());
        verify(runtime, never()).discoverResumes(any());
        verify(runtime, never()).readResumeText(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"check-authorization", "discover-resumes", "resume-text"})
    void everyOperationWorksWhileTheAgentIsBusy(String operation) {
        runtimeReports("BUSY", true, profileId);
        profileExists();

        invoke(operation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"check-authorization", "discover-resumes", "resume-text"})
    void everyOperationRequiresAnAvailableAgent(String operation) {
        runtimeReports("OFFLINE", true, profileId);

        assertRuntimeUnavailable(() -> invoke(operation));

        verify(repository, never()).findByAccountId(any());
        verifyNoCommandWasCreated();
    }

    @ParameterizedTest
    @ValueSource(strings = {"check-authorization", "discover-resumes", "resume-text"})
    void everyOperationRequiresABrowserProfile(String operation) {
        noProfileExists();

        assertProfileNotFound(() -> invoke(operation));

        verifyNoCommandWasCreated();
    }

    @ParameterizedTest
    @ValueSource(strings = {"check-authorization", "discover-resumes", "resume-text"})
    void everyOperationRequiresAnOpenBrowser(String operation) {
        profileExists();
        runtimeReports("READY", false, profileId);

        assertBrowserNotRunning(() -> invoke(operation));

        verifyNoCommandWasCreated();
    }

    @ParameterizedTest
    @ValueSource(strings = {"check-authorization", "discover-resumes", "resume-text"})
    void everyOperationRequiresTheBrowserOfThatVeryProfile(String operation) {
        profileExists();
        runtimeReports("READY", true, otherProfileId);

        assertBrowserNotRunning(() -> invoke(operation));

        verifyNoCommandWasCreated();
    }

    @ParameterizedTest
    @ValueSource(strings = {"check-authorization", "discover-resumes", "resume-text"})
    void everyOperationRequiresAKnownActiveProfile(String operation) {
        profileExists();
        runtimeReports("READY", true, null);

        assertBrowserNotRunning(() -> invoke(operation));

        verifyNoCommandWasCreated();
    }

    @Test
    void authorizationCheckIsRoutedWithTheFoundProfile() {
        profileExists();
        when(runtime.checkAuthorization(profile)).thenReturn(commandId);

        assertEquals(commandId, service.checkAuthorization(accountId));

        verify(runtime).checkAuthorization(profile);
        verify(runtime, never()).discoverResumes(any());
    }

    @Test
    void resumeDiscoveryIsRoutedWithTheFoundProfile() {
        profileExists();
        when(runtime.discoverResumes(profile)).thenReturn(commandId);

        assertEquals(commandId, service.discoverResumes(accountId));

        verify(runtime).discoverResumes(profile);
        verify(runtime, never()).checkAuthorization(any());
    }

    @Test
    void resumeTextReadingForwardsBothTheProfileAndTheRequestedResume() {
        profileExists();
        when(runtime.readResumeText(profile, HH_RESUME_ID)).thenReturn(commandId);

        assertEquals(commandId, service.readResumeText(accountId, HH_RESUME_ID));

        verify(runtime).readResumeText(profile, HH_RESUME_ID);
    }

    /**
     * Фиксирует границу ответственности: формат идентификатора резюме проверяет HTTP-DTO,
     * доменный сервис передаёт значение в порт как есть.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ABCDEF0123456789ABCD", "not-a-resume", "   ", "abc"})
    void serviceDoesNotValidateTheResumeIdentifierItself(String hhResumeId) {
        profileExists();
        when(runtime.readResumeText(profile, hhResumeId)).thenReturn(commandId);

        assertEquals(commandId, service.readResumeText(accountId, hhResumeId));

        verify(runtime).readResumeText(profile, hhResumeId);
    }

    @Test
    void operationsNeverTouchTheAccountModule() {
        profileExists();

        service.checkAuthorization(accountId);
        service.discoverResumes(accountId);
        service.readResumeText(accountId, HH_RESUME_ID);

        verifyNoInteractions(accounts);
    }
}
