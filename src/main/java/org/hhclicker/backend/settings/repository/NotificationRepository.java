package org.hhclicker.backend.settings.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.model.NotificationSettings;

import java.util.List;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public NotificationSettings settings() {
        return jdbc.sql("select * from notification_settings where id = 1").query((rs, n) ->
                new NotificationSettings(rs.getBoolean("desktop_enabled"), rs.getBoolean("sound_enabled"),
                        rs.getBoolean("notify_run_completed"), rs.getBoolean("notify_run_paused"),
                        rs.getBoolean("notify_attention_required"), rs.getBoolean("notify_relevance_completed"),
                        rs.getBoolean("notify_market_completed"))).single();
    }

    public void save(NotificationSettings settings) {
        jdbc.sql("""
                        update notification_settings set desktop_enabled = :desktop, sound_enabled = :sound,
                          notify_run_completed = :completed, notify_run_paused = :paused,
                          notify_attention_required = :attention,
                          notify_relevance_completed = :relevance,
                          notify_market_completed = :market where id = 1
                        """).param("desktop", settings.desktopEnabled()).param("sound", settings.soundEnabled())
                .param("completed", settings.notifyRunCompleted()).param("paused", settings.notifyRunPaused())
                .param("attention", settings.notifyAttentionRequired())
                .param("relevance", settings.notifyRelevanceCompleted())
                .param("market", settings.notifyMarketCompleted()).update();
    }

    // A compact, single-statement snapshot across all accounts; no cover letters or full history payloads.
    public List<NotificationRun> runs() {
        return jdbc.sql("""
                with recent as (select * from application_run order by sequence desc limit 100)
                select r.id, r.account_id, r.resume_id, r.resume_name, r.sequence, r.status, r.mode,
                  r.next_at::text as revision, count(i.id) as total,
                  count(i.id) filter (where i.status = 'SUCCEEDED') as succeeded,
                  count(i.id) filter (where i.status = 'FAILED') as failed,
                  count(i.id) filter (where i.status not in ('QUEUED', 'IN_PROGRESS')) as processed,
                  r.active_seconds + case when r.status = 'RUNNING' and r.active_since is not null
                    then greatest(0, floor(extract(epoch from (clock_timestamp() - r.active_since))))::bigint
                    else 0 end as active_seconds,
                  greatest(0, count(i.id) filter (where i.status not in ('QUEUED', 'IN_PROGRESS'))
                    - r.eta_processed_baseline) as timed_processed,
                  coalesce(string_agg(i.id::text, ',' order by i.position)
                    filter (where i.status = 'ATTENTION_REQUIRED' and i.attention_status = 'OPEN'), '') as attention_ids
                from recent r left join application_item i on i.run_id = r.id
                group by r.id, r.account_id, r.resume_id, r.resume_name, r.sequence, r.status, r.mode, r.next_at,
                  r.active_seconds, r.active_since, r.eta_processed_baseline
                order by r.sequence
                """).query((rs, n) -> {
            String ids = rs.getString("attention_ids");
            return new NotificationRun(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                    rs.getObject("resume_id", UUID.class), rs.getString("resume_name"), rs.getLong("sequence"),
                    rs.getString("status"), rs.getString("revision"), rs.getLong("total"), rs.getLong("succeeded"),
                    rs.getLong("failed"), rs.getLong("processed"), rs.getLong("active_seconds"),
                    rs.getLong("timed_processed"),
                    ids.isEmpty() ? List.of() : List.of(ids.split(",")), rs.getString("mode"));
        }).list();
    }
}
