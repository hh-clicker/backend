package org.hhclicker.backend.settings.telegram;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramConnectionServiceTests {
    private static final String TOKEN = "00000:HH_CLICKER_TEST_TOKEN_NOT_A_REAL_SECRET";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private final TelegramConnectionRepository repository = mock(TelegramConnectionRepository.class);
    private final TelegramBotApi api = mock(TelegramBotApi.class);
    private final TelegramConnectionService service = new TelegramConnectionService(repository, api,
            Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

    @Test
    void connectVerifiesBotAndCreatesExpiringOneTimeLinkWithoutReturningToken() {
        when(api.identity(TOKEN)).thenReturn(new TelegramBotApi.BotIdentity(42, "hh_clicker_bot"));
        when(api.webhookUrl(TOKEN)).thenReturn("");
        when(repository.get()).thenReturn(new TelegramConnection(TOKEN, 42L, "hh_clicker_bot", null, null,
                "saved-code", OffsetDateTime.ofInstant(NOW.plusSeconds(900), ZoneOffset.UTC), 0, null));

        TelegramConnectionService.View view = service.connect("  " + TOKEN + "  ");

        assertEquals("WAITING_FOR_START", view.status());
        assertEquals("https://t.me/hh_clicker_bot?start=saved-code", view.pairingUrl());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(repository).beginPairing(eq(TOKEN), eq(42L), eq("hh_clicker_bot"), code.capture(),
                eq(OffsetDateTime.ofInstant(NOW.plusSeconds(900), ZoneOffset.UTC)));
        assertFalse(code.getValue().isBlank());
    }

    @Test
    void connectRejectsBotThatAlreadyUsesWebhook() {
        when(api.identity(TOKEN)).thenReturn(new TelegramBotApi.BotIdentity(42, "hh_clicker_bot"));
        when(api.webhookUrl(TOKEN)).thenReturn("https://example.test/hook");

        ApplicationException failure = assertThrows(ApplicationException.class, () -> service.connect(TOKEN));

        assertEquals("TELEGRAM_WEBHOOK_ACTIVE", failure.machineCode());
        verify(repository, never()).beginPairing(any(), anyLong(), any(), any(), any());
    }

    @Test
    void pairingAcceptsOnlyExactStartCommandFromPrivateChat() {
        TelegramConnection connection = waitingConnection();
        when(repository.bind(TOKEN, "pair-code", 100, 200, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(true);

        assertFalse(service.accept(connection, new TelegramBotApi.Update(1,
                new TelegramBotApi.Message(100, "group", 200L, "/start pair-code"))));
        assertFalse(service.accept(connection, new TelegramBotApi.Update(2,
                new TelegramBotApi.Message(100, "private", 200L, "/start another-code"))));
        assertTrue(service.accept(connection, new TelegramBotApi.Update(3,
                new TelegramBotApi.Message(100, "private", 200L, "/start pair-code"))));
        verify(repository, times(1)).bind(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void testMessageRequiresBoundChat() {
        when(repository.get()).thenReturn(waitingConnection());

        ApplicationException failure = assertThrows(ApplicationException.class, service::test);

        assertEquals("TELEGRAM_NOT_CONNECTED", failure.machineCode());
        verifyNoInteractions(api);
    }

    private TelegramConnection waitingConnection() {
        return new TelegramConnection(TOKEN, 42L, "hh_clicker_bot", null, null, "pair-code",
                OffsetDateTime.ofInstant(NOW.plusSeconds(900), ZoneOffset.UTC), 0, null);
    }
}
