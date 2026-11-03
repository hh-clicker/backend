package org.hhclicker.backend.settings.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpTelegramBotApiTests {
    private static final String TOKEN = "00000:HH_CLICKER_TEST_TOKEN_NOT_A_REAL_SECRET";

    private MockRestServiceServer server;
    private HttpTelegramBotApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        api = new HttpTelegramBotApi(builder, "https://telegram.test");
    }

    @Test
    void identityReadsTelegramSnakeCaseFields() {
        server.expect(once(), method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"id":42,"is_bot":true,"username":"hh_clicker_bot"}}
                        """, MediaType.APPLICATION_JSON));

        TelegramBotApi.BotIdentity identity = api.identity(TOKEN);

        assertEquals(42, identity.id());
        assertEquals("hh_clicker_bot", identity.username());
        server.verify();
    }

    @Test
    void updatesReadIdsAndPrivateStartMessage() {
        server.expect(once(), method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":17,"message":{"message_id":9,"from":{"id":200,"is_bot":false,"username":"user"},"chat":{"id":100,"type":"private"},"text":"/start code"}}]}
                        """, MediaType.APPLICATION_JSON));

        TelegramBotApi.Update update = api.updates(TOKEN, 12, 20).getFirst();

        assertEquals(17, update.id());
        assertEquals(100, update.message().chatId());
        assertEquals(200, update.message().userId());
        assertEquals("private", update.message().chatType());
        assertEquals("/start code", update.message().text());
        server.verify();
    }

    @Test
    void updatesReadCallbackQueries() {
        server.expect(once(), method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":18,"callback_query":{"id":"callback-1","from":{"id":200,"is_bot":false},"message":{"message_id":44,"chat":{"id":100,"type":"private"}},"data":"application:pause:10000000-0000-0000-0000-000000000001"}}]}
                        """, MediaType.APPLICATION_JSON));

        TelegramBotApi.Callback callback = api.updates(TOKEN, 18, 20).getFirst().callback();

        assertEquals("callback-1", callback.id());
        assertEquals(100, callback.chatId());
        assertEquals(44, callback.messageId());
        assertEquals(200, callback.userId());
        server.verify();
    }

    @Test
    void sendMessageIncludesInlineKeyboardAndReturnsMessageId() {
        server.expect(once(), method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"chat_id":100,"text":"Статус","reply_markup":{"inline_keyboard":[[{"text":"Пауза","callback_data":"application:pause:id"}]]}}
                        """))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"message_id":44,"chat":{"id":100,"type":"private"}}}
                        """, MediaType.APPLICATION_JSON));

        long messageId = api.sendMessage(TOKEN, 100, "Статус",
                java.util.List.of(new TelegramBotApi.InlineButton("Пауза", "application:pause:id")));

        assertEquals(44, messageId);
        server.verify();
    }

    @Test
    void deleteMessageSendsChatAndMessageIds() {
        server.expect(once(), method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"chat_id":100,"message_id":44}
                        """))
                .andRespond(withSuccess("""
                        {"ok":true,"result":true}
                        """, MediaType.APPLICATION_JSON));

        api.deleteMessage(TOKEN, 100, 44);

        server.verify();
    }

    @Test
    void editReportsThatTheMessageWasDeleted() {
        server.expect(once(), method(HttpMethod.POST))
                .andRespond(withBadRequest().body("""
                        {"ok":false,"error_code":400,"description":"Bad Request: message to edit not found"}
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThrows(TelegramMessageNotFoundException.class,
                () -> api.editMessage(TOKEN, 100, 44, "Статус", java.util.List.of()));

        server.verify();
    }

    @Test
    void editingAnUnchangedMessageConfirmsThatItStillExists() {
        server.expect(once(), method(HttpMethod.POST))
                .andRespond(withBadRequest().body("""
                        {"ok":false,"error_code":400,"description":"Bad Request: message is not modified"}
                        """).contentType(MediaType.APPLICATION_JSON));

        api.editMessage(TOKEN, 100, 44, "Статус", java.util.List.of());

        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 500})
    void httpFailuresNeverRetainTokenOrEchoedResponseInThrowableTree(int status) {
        server.expect(once(), method(HttpMethod.POST)).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.valueOf(status))
                        .body("Request failed at https://telegram.test/bot" + TOKEN + "/getMe"));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> api.identity(TOKEN));
        assertSanitized(failure);
        server.verify();
    }

    @Test
    void transportFailureNeverRetainsSecretUrl() {
        server.expect(once(), method(HttpMethod.POST)).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withException(
                        new java.io.IOException("Transport failed at /bot" + TOKEN + "/getMe")));
        assertSanitized(assertThrows(RuntimeException.class, () -> api.identity(TOKEN)));
        server.verify();
    }

    @Test
    void deletedMessageKeepsItsClassificationWithoutRetainingEchoedToken() {
        server.expect(once(), method(HttpMethod.POST)).andRespond(withBadRequest()
                .body("message to edit not found " + TOKEN));
        assertSanitized(assertThrows(TelegramMessageNotFoundException.class,
                () -> api.editMessage(TOKEN, 100, 44, "Статус", java.util.List.of())));
        server.verify();
    }

    private void assertSanitized(Throwable failure) {
        java.io.StringWriter output = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(output));
        org.junit.jupiter.api.Assertions.assertFalse(output.toString().contains(TOKEN));
        org.junit.jupiter.api.Assertions.assertFalse(output.toString().contains("/bot"));
    }
}
