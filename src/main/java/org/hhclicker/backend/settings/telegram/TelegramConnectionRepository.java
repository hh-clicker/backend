package org.hhclicker.backend.settings.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class TelegramConnectionRepository {
    private final JdbcClient jdbc;

    public TelegramConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TelegramConnection get() {
        return jdbc.sql("select * from telegram_connection where id = 1").query((rs, row) ->
                new TelegramConnection(rs.getString("bot_token"), nullableLong(rs, "bot_id"),
                        rs.getString("bot_username"), nullableLong(rs, "chat_id"),
                        nullableLong(rs, "telegram_user_id"), rs.getString("pairing_code"),
                        rs.getObject("pairing_expires_at", OffsetDateTime.class), rs.getLong("update_offset"),
                        rs.getString("last_error"))).single();
    }

    public void beginPairing(String token, long botId, String username, String code, OffsetDateTime expiresAt) {
        jdbc.sql("""
                update telegram_connection set bot_token = :token, bot_id = :botId, bot_username = :username,
                  chat_id = null, telegram_user_id = null, pairing_code = :code, pairing_expires_at = :expiresAt,
                  update_offset = 0, last_error = null, updated_at = now() where id = 1
                """).param("token", token).param("botId", botId).param("username", username)
                .param("code", code).param("expiresAt", expiresAt).update();
    }

    public boolean bind(String token, String code, long chatId, long userId, OffsetDateTime now) {
        return jdbc.sql("""
                update telegram_connection set chat_id = :chatId, telegram_user_id = :userId,
                  pairing_code = null, pairing_expires_at = null, last_error = null, updated_at = now()
                where id = 1 and bot_token = :token and pairing_code = :code and pairing_expires_at > :now
                  and chat_id is null
                """).param("chatId", chatId).param("userId", userId).param("token", token)
                .param("code", code).param("now", now).update() == 1;
    }

    public void advanceOffset(String token, long offset) {
        jdbc.sql("""
                update telegram_connection set update_offset = greatest(update_offset, :offset), updated_at = now()
                where id = 1 and bot_token = :token
                """).param("offset", offset).param("token", token).update();
    }

    public void recordError(String token, String message) {
        jdbc.sql("""
                update telegram_connection set last_error = :message, updated_at = now()
                where id = 1 and bot_token = :token
                """).param("message", message).param("token", token).update();
    }

    public void clearError(String token) {
        jdbc.sql("""
                update telegram_connection set last_error = null, updated_at = now()
                where id = 1 and bot_token = :token
                """).param("token", token).update();
    }

    public void disconnect() {
        jdbc.sql("""
                update telegram_connection set bot_token = null, bot_id = null, bot_username = null,
                  chat_id = null, telegram_user_id = null, pairing_code = null, pairing_expires_at = null,
                  update_offset = 0, last_error = null, updated_at = now() where id = 1
                """).update();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
