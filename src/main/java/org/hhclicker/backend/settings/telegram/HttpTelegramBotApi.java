package org.hhclicker.backend.settings.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.hhclicker.backend.shared.exception.ApplicationErrorCategory;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.shared.config.ExternalHttpClient;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HttpTelegramBotApi implements TelegramBotApi {
    private final RestClient client;

    public HttpTelegramBotApi() {
        this(ExternalHttpClient.telegram(), "https://api.telegram.org");
    }

    HttpTelegramBotApi(RestClient.Builder builder, String baseUrl) {
        client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public BotIdentity identity(String token) {
        BotEnvelope response = call(token, "getMe", Map.of(), BotEnvelope.class);
        if (response.result() == null || !response.result().isBot() || response.result().username() == null) {
            throw invalidResponse();
        }
        return new BotIdentity(response.result().id(), response.result().username());
    }

    @Override
    public String webhookUrl(String token) {
        WebhookEnvelope response = call(token, "getWebhookInfo", Map.of(), WebhookEnvelope.class);
        return response.result() == null || response.result().url() == null ? "" : response.result().url();
    }

    @Override
    public List<Update> updates(String token, long offset, int timeoutSeconds) {
        UpdatesEnvelope response = call(token, "getUpdates", Map.of(
                "offset", offset,
                "timeout", timeoutSeconds,
                "allowed_updates", List.of("message", "callback_query")
        ), UpdatesEnvelope.class);
        if (response.result() == null) throw invalidResponse();
        return response.result().stream().map(update -> new Update(update.updateId(), toMessage(update.message()),
                toCallback(update.callbackQuery()))).toList();
    }

    @Override
    public long sendMessage(String token, long chatId, String text, List<InlineButton> buttons) {
        MessageEnvelope response = call(token, "sendMessage", messageBody(chatId, null, text, buttons),
                MessageEnvelope.class);
        if (response.result() == null) throw invalidResponse();
        return response.result().messageId();
    }

    @Override
    public void editMessage(String token, long chatId, long messageId, String text, List<InlineButton> buttons) {
        try {
            call(token, "editMessageText", messageBody(chatId, messageId, text, buttons), MessageEnvelope.class);
        } catch (ApplicationException exception) {
            if (isBadRequest(exception, "message is not modified")) return;
            if (isBadRequest(exception, "message to edit not found")) {
                throw new TelegramMessageNotFoundException(exception);
            }
            throw exception;
        }
    }

    @Override
    public void deleteMessage(String token, long chatId, long messageId) {
        try {
            call(token, "deleteMessage", Map.of("chat_id", chatId, "message_id", messageId), OkEnvelope.class);
        } catch (ApplicationException exception) {
            if (isBadRequest(exception, "message to delete not found")) return;
            throw exception;
        }
    }

    private boolean isBadRequest(ApplicationException exception, String description) {
        return exception instanceof SafeTelegramFailure failure && failure.description.equals(description);
    }

    @Override
    public void answerCallback(String token, String callbackId, String text) {
        call(token, "answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", text), OkEnvelope.class);
    }

    private <T extends Envelope> T call(String token, String method, Object body, Class<T> type) {
        try {
            T response = client.post().uri("/bot{token}/{method}", token, method).body(body).retrieve().body(type);
            if (response == null || !response.ok()) throw invalidResponse();
            return response;
        } catch (HttpClientErrorException.BadRequest exception) {
            String bodyText = exception.getResponseBodyAsString();
            String knownDescription = List.of("message is not modified", "message to edit not found",
                            "message to delete not found").stream()
                    .filter(bodyText::contains).findFirst().orElse("");
            throw new SafeTelegramFailure(knownDescription);
        } catch (ApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // HTTP exceptions contain the request URL with /bot<TOKEN> and possibly its echoed body.
            // Neither their cause nor their raw message may escape into worker/controller logs.
            throw new SafeTelegramFailure("");
        }
    }

    private static final class SafeTelegramFailure extends ApplicationException {
        private final String description;

        private SafeTelegramFailure(String description) {
            super(ApplicationErrorCategory.EXTERNAL_SYSTEM, "TELEGRAM_UNAVAILABLE",
                    "Не удалось связаться с Telegram. Проверьте токен и подключение к интернету.");
            this.description = description;
        }
    }

    private ApplicationException invalidResponse() {
        return new ApplicationException(ApplicationErrorCategory.EXTERNAL_SYSTEM, "TELEGRAM_INVALID_RESPONSE",
                "Telegram вернул неожиданный ответ.");
    }

    private Message toMessage(ApiMessage message) {
        if (message == null || message.chat() == null) return null;
        return new Message(message.chat().id(), message.chat().type(),
                message.from() == null ? null : message.from().id(), message.text());
    }

    private Callback toCallback(ApiCallbackQuery callback) {
        if (callback == null || callback.message() == null || callback.message().chat() == null) return null;
        return new Callback(callback.id(), callback.message().chat().id(), callback.message().messageId(),
                callback.from() == null ? null : callback.from().id(), callback.data());
    }

    private Map<String, Object> messageBody(long chatId, Long messageId, String text, List<InlineButton> buttons) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        if (messageId != null) body.put("message_id", messageId);
        body.put("text", text);
        body.put("reply_markup", Map.of("inline_keyboard", buttons.isEmpty() ? List.of() : List.of(
                buttons.stream().map(button -> Map.of("text", button.text(), "callback_data", button.data())).toList()
        )));
        return body;
    }

    private interface Envelope {
        boolean ok();
    }

    private record BotEnvelope(boolean ok, ApiUser result) implements Envelope {
    }

    private record WebhookEnvelope(boolean ok, ApiWebhook result) implements Envelope {
    }

    private record UpdatesEnvelope(boolean ok, List<ApiUpdate> result) implements Envelope {
    }

    private record MessageEnvelope(boolean ok, ApiMessage result) implements Envelope {
    }

    private record OkEnvelope(boolean ok) implements Envelope {
    }

    private record ApiUser(long id, @JsonProperty("is_bot") boolean isBot, String username) {
    }

    private record ApiWebhook(String url) {
    }

    private record ApiUpdate(@JsonProperty("update_id") long updateId, ApiMessage message,
                             @JsonProperty("callback_query") ApiCallbackQuery callbackQuery) {
    }

    private record ApiMessage(@JsonProperty("message_id") long messageId, ApiChat chat, ApiUser from, String text) {
    }

    private record ApiChat(long id, String type) {
    }

    private record ApiCallbackQuery(String id, ApiUser from, ApiMessage message, String data) {
    }
}
