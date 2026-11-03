package org.hhclicker.backend.application.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class TelegramRunCompletionRepository {
    private final JdbcClient jdbc;

    public TelegramRunCompletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Message> find(UUID runId, TelegramMessageService.Target target) {
        return jdbc.sql("""
                select message_id, fingerprint, confirmed, cleaned from telegram_run_completion_message
                where run_id = :run and bot_id = :bot and chat_id = :chat
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> message(rs.getLong("message_id"), rs.getString("fingerprint"),
                        rs.getBoolean("confirmed"), rs.getBoolean("cleaned"))).optional();
    }

    public Map<UUID, Message> findAll(TelegramMessageService.Target target) {
        return jdbc.sql("""
                select run_id, message_id, fingerprint, confirmed, cleaned from telegram_run_completion_message
                where bot_id = :bot and chat_id = :chat and not cleaned
                """).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> new RunMessage(rs.getObject("run_id", UUID.class),
                        message(rs.getLong("message_id"), rs.getString("fingerprint"),
                                rs.getBoolean("confirmed"), rs.getBoolean("cleaned"))))
                .list().stream().collect(Collectors.toMap(RunMessage::runId, RunMessage::message));
    }

    public void save(UUID runId, TelegramMessageService.Target target, long messageId, String fingerprint) {
        jdbc.sql("""
                insert into telegram_run_completion_message (run_id, bot_id, chat_id, message_id, fingerprint)
                values (:run, :bot, :chat, :message, :fingerprint)
                on conflict (run_id, bot_id, chat_id) do nothing
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId())
                .param("message", messageId).param("fingerprint", fingerprint).update();
    }

    public void confirm(UUID runId, TelegramMessageService.Target target) {
        jdbc.sql("""
                update telegram_run_completion_message set confirmed = true, updated_at = now()
                where run_id = :run and bot_id = :bot and chat_id = :chat and not confirmed
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId()).update();
    }

    public void update(UUID runId, TelegramMessageService.Target target, String fingerprint, boolean cleaned) {
        jdbc.sql("""
                update telegram_run_completion_message set fingerprint = :fingerprint, cleaned = :cleaned,
                  updated_at = now() where run_id = :run and bot_id = :bot and chat_id = :chat
                """).param("fingerprint", fingerprint).param("cleaned", cleaned).param("run", runId)
                .param("bot", target.botId()).param("chat", target.chatId()).update();
    }

    private static Message message(long messageId, String fingerprint, boolean confirmed, boolean cleaned) {
        return new Message(messageId, fingerprint, confirmed, cleaned);
    }

    public record Message(long messageId, String fingerprint, boolean confirmed, boolean cleaned) {
    }

    private record RunMessage(UUID runId, Message message) {
    }
}
