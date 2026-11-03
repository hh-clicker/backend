package org.hhclicker.backend.application.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.Optional;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class TelegramRunMessageRepository {
    private final JdbcClient jdbc;

    public TelegramRunMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Message> find(UUID runId, TelegramMessageService.Target target) {
        return jdbc.sql("""
                select message_id, fingerprint from telegram_run_message
                where run_id = :run and bot_id = :bot and chat_id = :chat
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> new Message(rs.getLong("message_id"), rs.getString("fingerprint"))).optional();
    }

    public Map<UUID, Message> findAll(TelegramMessageService.Target target) {
        return jdbc.sql("""
                select run_id, message_id, fingerprint from telegram_run_message
                where bot_id = :bot and chat_id = :chat and completion_eligible
                """).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> new RunMessage(rs.getObject("run_id", UUID.class),
                        new Message(rs.getLong("message_id"), rs.getString("fingerprint"))))
                .list().stream().collect(Collectors.toMap(RunMessage::runId, RunMessage::message));
    }

    public void save(UUID runId, TelegramMessageService.Target target, long messageId, String fingerprint) {
        jdbc.sql("""
                insert into telegram_run_message (run_id, bot_id, chat_id, message_id, fingerprint, completion_eligible)
                values (:run, :bot, :chat, :message, :fingerprint, true)
                on conflict (run_id, bot_id, chat_id) do update set message_id = excluded.message_id,
                  fingerprint = excluded.fingerprint, completion_eligible = true, updated_at = now()
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId())
                .param("message", messageId).param("fingerprint", fingerprint).update();
    }

    public void updateFingerprint(UUID runId, TelegramMessageService.Target target, String fingerprint) {
        jdbc.sql("""
                update telegram_run_message set fingerprint = :fingerprint, updated_at = now()
                where run_id = :run and bot_id = :bot and chat_id = :chat
                """).param("fingerprint", fingerprint).param("run", runId).param("bot", target.botId())
                .param("chat", target.chatId()).update();
    }

    public List<UUID> completionCandidates(TelegramMessageService.Target target) {
        return jdbc.sql("""
                select m.run_id from telegram_run_message m join application_run r on r.id = m.run_id
                where m.bot_id = :bot and m.chat_id = :chat and m.completion_eligible
                  and r.status in ('COMPLETED', 'CANCELLED')
                  and not exists(select 1 from telegram_run_completion_message c
                    where c.run_id = m.run_id and c.bot_id = m.bot_id and c.chat_id = m.chat_id)
                """).param("bot", target.botId()).param("chat", target.chatId())
                .query((rs, row) -> rs.getObject("run_id", UUID.class)).list();
    }

    public void markCompletionHandled(UUID runId, TelegramMessageService.Target target) {
        jdbc.sql("""
                update telegram_run_message set completion_eligible = false, updated_at = now()
                where run_id = :run and bot_id = :bot and chat_id = :chat
                """).param("run", runId).param("bot", target.botId()).param("chat", target.chatId()).update();
    }

    public record Message(long messageId, String fingerprint) {
    }

    private record RunMessage(UUID runId, Message message) {
    }
}
