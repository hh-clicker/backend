package org.hhclicker.backend.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Запуск и остановка браузера: доступность Browser Agent и передача профиля в порт.
 */
class BrowserLifecycleTests extends BrowserServiceTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"READY", "BUSY"})
    void availableAgentAllowsStartingTheBrowser(String agentStatus) {
        runtimeReports(agentStatus, false, null);
        profileExists();
        when(runtime.open(profile)).thenReturn(commandId);

        assertEquals(commandId, service.open(accountId));

        verify(runtime).open(profile);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"OFFLINE", "CONNECTING", "ready", "busy", "STARTING", ""})
    void unavailableAgentStopsTheStartBeforeAnythingElse(String agentStatus) {
        runtimeReports(agentStatus, false, null);

        assertRuntimeUnavailable(() -> service.open(accountId));

        verify(repository, never()).findByAccountId(any());
        verify(runtime, never()).open(any());
    }

    @Test
    void accountWithoutABrowserProfileCannotStartTheBrowser() {
        noProfileExists();

        assertProfileNotFound(() -> service.open(accountId));

        verify(runtime, never()).open(any());
    }

    /**
     * Фиксирует текущий контракт: запуск не проверяет, открыт ли уже браузер и какой профиль
     * активен — решение остаётся за очередью команд Browser Agent.
     */
    @Test
    void startIsAllowedEvenWhenAnotherProfileIsAlreadyOpen() {
        runtimeReports("READY", true, otherProfileId);
        profileExists();
        when(runtime.open(profile)).thenReturn(commandId);

        assertEquals(commandId, service.open(accountId));
    }

    @Test
    void gatewayFailureDuringStartIsNotHidden() {
        profileExists();
        when(runtime.open(profile)).thenThrow(new IllegalStateException("Очередь занята"));

        assertThrows(IllegalStateException.class, () -> service.open(accountId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "BUSY"})
    void availableAgentAllowsStoppingTheBrowser(String agentStatus) {
        runtimeReports(agentStatus, true, profileId);
        when(runtime.close()).thenReturn(commandId);

        assertEquals(commandId, service.close());

        verify(runtime).close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"OFFLINE", "CONNECTING", "unknown"})
    void unavailableAgentCannotBeAskedToStop(String agentStatus) {
        runtimeReports(agentStatus, true, profileId);

        assertRuntimeUnavailable(() -> service.close());

        verify(runtime, never()).close();
    }

    /**
     * Остановка не требует ни профиля, ни открытого браузера — это текущий контракт.
     */
    @Test
    void stoppingDoesNotRequireAnOpenBrowserOrAProfile() {
        runtimeReports("READY", false, null);
        when(runtime.close()).thenReturn(commandId);

        assertEquals(commandId, service.close());

        verify(repository, never()).findByAccountId(any());
    }

    @Test
    void gatewayFailureDuringStopIsNotHidden() {
        when(runtime.close()).thenThrow(new IllegalStateException("Агент отвалился"));

        assertThrows(IllegalStateException.class, () -> service.close());
    }

    @Test
    void commandIdentifierOfTheGatewayIsReturnedUnchanged() {
        UUID startCommand = UUID.randomUUID();
        UUID stopCommand = UUID.randomUUID();
        profileExists();
        when(runtime.open(profile)).thenReturn(startCommand);
        when(runtime.close()).thenReturn(stopCommand);

        assertEquals(startCommand, service.open(accountId));
        assertEquals(stopCommand, service.close());
    }
}
