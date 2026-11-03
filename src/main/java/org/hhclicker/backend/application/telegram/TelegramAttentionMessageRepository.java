package org.hhclicker.backend.application.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class TelegramAttentionMessageRepository {
    private final JdbcClient jdbc;

    public TelegramAttentionMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, Message> findOpen(TelegramMessageService.Target target) {
        return jdbc.sql("""
                select attention_id, message_id, fingerprint from telegram_attention_message
                where bot_id = :bot and chat_id = :chat and not closed
                """).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> new AttentionMessage(rs.getObject("attention_id", UUID.class),
                        new Message(rs.getLong("message_id"), rs.getString("fingerprint"))))
                .list().stream().collect(Collectors.toMap(AttentionMessage::attentionId, AttentionMessage::message));
    }

    public void save(UUID attentionId, TelegramMessageService.Target target, long messageId, String fingerprint) {
        jdbc.sql("""
                insert into telegram_attention_message (attention_id, bot_id, chat_id, message_id, fingerprint)
                values (:attention, :bot, :chat, :message, :fingerprint)
                on conflict (attention_id, bot_id, chat_id) do update set message_id = excluded.message_id,
                  fingerprint = excluded.fingerprint, closed = false, updated_at = now()
                """).param("attention", attentionId).param("bot", target.botId()).param("chat", target.chatId())
                .param("message", messageId).param("fingerprint", fingerprint).update();
    }

    public void update(UUID attentionId, TelegramMessageService.Target target, String fingerprint, boolean closed) {
        jdbc.sql("""
                update telegram_attention_message set fingerprint = :fingerprint, closed = :closed, updated_at = now()
                where attention_id = :attention and bot_id = :bot and chat_id = :chat
                """).param("fingerprint", fingerprint).param("closed", closed).param("attention", attentionId)
                .param("bot", target.botId()).param("chat", target.chatId()).update();
    }

    public List<Long> messageIds(UUID runId, TelegramMessageService.Target target) {
        return jdbc.sql("""
                select m.message_id from telegram_attention_message m
                  join application_item i on i.id = m.attention_id
                where i.run_id = :run and m.bot_id = :bot and m.chat_id = :chat
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> rs.getLong("message_id")).list();
    }

    public void closeRun(UUID runId, TelegramMessageService.Target target) {
        jdbc.sql("""
                update telegram_attention_message m set closed = true, updated_at = now()
                from application_item i
                where i.id = m.attention_id and i.run_id = :run
                  and m.bot_id = :bot and m.chat_id = :chat
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId()).update();
    }

    public record Message(long messageId, String fingerprint) {
    }

    private record AttentionMessage(UUID attentionId, Message message) {
    }
}
