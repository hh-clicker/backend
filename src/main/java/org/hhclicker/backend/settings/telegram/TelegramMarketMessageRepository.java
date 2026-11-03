package org.hhclicker.backend.settings.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class TelegramMarketMessageRepository {
    private final JdbcClient jdbc;

    public TelegramMarketMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, Message> findAll(TelegramMessageService.Target target) {
        return jdbc.sql("""
                select run_id, message_id, fingerprint from telegram_market_message
                where bot_id = :bot and chat_id = :chat
                """).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> new RunMessage(rs.getObject("run_id", UUID.class),
                        new Message(rs.getLong("message_id"), rs.getString("fingerprint"))))
                .list().stream().collect(Collectors.toMap(RunMessage::runId, RunMessage::message));
    }

    public void save(UUID runId, TelegramMessageService.Target target, long messageId, String fingerprint) {
        jdbc.sql("""
                insert into telegram_market_message (run_id, bot_id, chat_id, message_id, fingerprint)
                values (:run, :bot, :chat, :message, :fingerprint)
                on conflict (run_id, bot_id, chat_id) do update set message_id = excluded.message_id,
                  fingerprint = excluded.fingerprint, updated_at = now()
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId())
                .param("message", messageId).param("fingerprint", fingerprint).update();
    }

    public void updateFingerprint(UUID runId, TelegramMessageService.Target target, String fingerprint) {
        jdbc.sql("""
                update telegram_market_message set fingerprint = :fingerprint, updated_at = now()
                where run_id = :run and bot_id = :bot and chat_id = :chat
                """).param("fingerprint", fingerprint).param("run", runId).param("bot", target.botId())
                .param("chat", target.chatId()).update();
    }

    public record Message(long messageId, String fingerprint) { }
    private record RunMessage(UUID runId, Message message) { }
}
