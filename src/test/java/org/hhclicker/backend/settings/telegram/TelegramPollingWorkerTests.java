package org.hhclicker.backend.settings.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelegramPollingWorkerTests {
    @Test
    void diagnosticRepresentationsDoNotExposeCredentials() {
        String token = "00000:HH_CLICKER_TEST_TOKEN_NOT_A_REAL_SECRET";
        TelegramConnection connection = new TelegramConnection(token, 42L, "test_bot", null, null,
                "SYNTHETIC_PRIVATE_PAIRING_CODE", null, 0, null);
        org.junit.jupiter.api.Assertions.assertFalse(connection.toString().contains(token));
        org.junit.jupiter.api.Assertions.assertFalse(connection.toString().contains("SYNTHETIC_PRIVATE_PAIRING_CODE"));
        org.junit.jupiter.api.Assertions.assertFalse(new TelegramSettingsController.ConnectRequest(token).toString().contains(token));
    }

    @Test
    void failingUpdateDoesNotDiscardTheRestOfTheReceivedBatch() {
        TelegramConnectionRepository repository = mock(TelegramConnectionRepository.class);
        TelegramConnectionService service = mock(TelegramConnectionService.class);
        TelegramBotApi api = mock(TelegramBotApi.class);
        TelegramPollingWorker worker = new TelegramPollingWorker(repository, service, api, List.of());
        String token = "00000:HH_CLICKER_TEST_TOKEN_NOT_A_REAL_SECRET";
        TelegramConnection connection = new TelegramConnection(token, 42L, "test_bot", null, null,
                null, null, 0, null);
        var first = new TelegramBotApi.Update(1, null, null);
        var second = new TelegramBotApi.Update(2, new TelegramBotApi.Message(100, "private", 200L, "/start"), null);
        when(api.updates(token, 0, 20)).thenReturn(List.of(first, second));
        when(service.accept(connection, first)).thenThrow(new IllegalStateException("Synthetic handler failure"));
        when(service.accept(connection, second)).thenReturn(true);

        worker.poll(connection);

        verify(service).accept(connection, second);
        verify(api).sendMessage(eq(token), eq(100L), anyString(), eq(List.of()));
        verify(repository).advanceOffset(token, 2);
        verify(repository).advanceOffset(token, 3);
        verify(repository).recordError(eq(token), anyString());
        verify(repository, never()).clearError(anyString());
    }
}
