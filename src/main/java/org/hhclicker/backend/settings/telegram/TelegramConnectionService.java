package org.hhclicker.backend.settings.telegram;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.regex.Pattern;

@Service
public class TelegramConnectionService {
    private static final Pattern TOKEN = Pattern.compile("^[0-9]{5,20}:[A-Za-z0-9_-]{20,220}$");
    private static final Duration PAIRING_TTL = Duration.ofMinutes(15);
    private static final String TEST_MESSAGE = "✅ HH Clicker подключён. Тестовое сообщение доставлено.";

    private final TelegramConnectionRepository repository;
    private final TelegramBotApi api;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public TelegramConnectionService(TelegramConnectionRepository repository, TelegramBotApi api) {
        this(repository, api, Clock.systemUTC(), new SecureRandom());
    }

    TelegramConnectionService(TelegramConnectionRepository repository, TelegramBotApi api, Clock clock,
                              SecureRandom random) {
        this.repository = repository;
        this.api = api;
        this.clock = clock;
        this.random = random;
    }

    public View state() {
        return view(repository.get(), now());
    }

    public View connect(String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (!TOKEN.matcher(token).matches()) {
            throw new TelegramSettingsException(ApplicationErrorCategory.VALIDATION, "INVALID_TELEGRAM_TOKEN",
                    "Токен Telegram-бота имеет неверный формат.");
        }
        TelegramBotApi.BotIdentity bot = api.identity(token);
        if (!api.webhookUrl(token).isBlank()) {
            throw new TelegramSettingsException(ApplicationErrorCategory.CONFLICT, "TELEGRAM_WEBHOOK_ACTIVE",
                    "У этого бота настроен webhook. Удалите его или используйте другого бота для HH Clicker.");
        }
        String code = pairingCode();
        OffsetDateTime expiresAt = now().plus(PAIRING_TTL);
        repository.beginPairing(token, bot.id(), bot.username(), code, expiresAt);
        return view(repository.get(), now());
    }

    public View test() {
        TelegramConnection connection = repository.get();
        if (!connection.connected()) {
            throw new TelegramSettingsException(ApplicationErrorCategory.CONFLICT, "TELEGRAM_NOT_CONNECTED",
                    "Сначала привяжите личный чат к Telegram-боту.");
        }
        api.sendMessage(connection.botToken(), connection.chatId(), TEST_MESSAGE, java.util.List.of());
        repository.clearError(connection.botToken());
        return view(repository.get(), now());
    }

    public View disconnect() {
        repository.disconnect();
        return view(repository.get(), now());
    }

    boolean accept(TelegramConnection connection, TelegramBotApi.Update update) {
        TelegramBotApi.Message message = update.message();
        if (message == null || !"private".equals(message.chatType()) || message.userId() == null
                || message.text() == null || !connection.pairingActive(now())) {
            return false;
        }
        String expected = "/start " + connection.pairingCode();
        if (!expected.equals(message.text().trim())) return false;
        return repository.bind(connection.botToken(), connection.pairingCode(), message.chatId(), message.userId(), now());
    }

    private View view(TelegramConnection connection, OffsetDateTime now) {
        if (!connection.configured()) return new View("DISCONNECTED", null, null, null, null);
        if (connection.connected()) return new View("CONNECTED", connection.botUsername(), null, null,
                connection.lastError());
        if (connection.pairingActive(now)) {
            return new View("WAITING_FOR_START", connection.botUsername(),
                    "https://t.me/" + connection.botUsername() + "?start=" + connection.pairingCode(),
                    connection.pairingExpiresAt(), connection.lastError());
        }
        return new View("PAIRING_EXPIRED", connection.botUsername(), null, null, connection.lastError());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String pairingCode() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record View(String status, String botUsername, String pairingUrl, OffsetDateTime pairingExpiresAt,
                       String lastError) {
    }
}
