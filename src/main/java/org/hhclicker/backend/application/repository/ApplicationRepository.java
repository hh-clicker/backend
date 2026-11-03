package org.hhclicker.backend.application.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.application.model.ApplicationRunEstimate;
import org.hhclicker.backend.application.model.ApplicationModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApplicationRepository {
    private final JdbcClient jdbc;

    public ApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Item item(ResultSet rs, int n) throws SQLException {
        String outcome = rs.getString("outcome");
        return new Item(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class), rs.getObject("vacancy_id", UUID.class),
                rs.getString("hh_vacancy_id"), rs.getString("vacancy_title"), rs.getString("company_name"), rs.getString("status"),
                outcome == null ? null : Outcome.valueOf(outcome), instant(rs, "attempted_at"),
                instant(rs, "submission_started_at"), rs.getBoolean("has_questionnaire"), rs.getString("attention_status"),
                rs.getString("letter_source") == null ? null : new PreparedLetter(rs.getString("letter_text"),
                        rs.getString("letter_source"), rs.getString("letter_error_code"), rs.getObject("generation_id", UUID.class)));
    }

    private static org.hhclicker.backend.resume.model.AiLetterSettings aiSettings(ResultSet rs) throws SQLException {
        return new org.hhclicker.backend.resume.model.AiLetterSettings(rs.getBoolean("ai_letter_enabled"),
                rs.getString("ai_letter_template"), rs.getInt("ai_letter_max_length"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    // All state transitions use this transaction-scoped lock, including submission permits.
    public void lock() {
        jdbc.sql("select set_config('lock_timeout', '5s', true)").query(String.class).single();
        jdbc.sql("select pg_advisory_xact_lock(724816302)").query((rs, n) -> 0).single();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("select exists(select 1 from application_run where id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    public boolean busy() {
        return jdbc.sql("""
                select exists(select 1 from application_run where status not in ('COMPLETED', 'CANCELLED'))
                  or exists(select 1 from application_item where status = 'IN_PROGRESS')
                """).query(Boolean.class).single();
    }

    public Optional<Run> find(UUID id) {
        return jdbc.sql("select * from application_run where id = :id").param("id", id).query(this::run).optional();
    }

    public boolean inspectionAllowed(UUID profileId) {
        return jdbc.sql("""
                select not exists(select 1 from application_run where status = 'RUNNING')
                  and not exists(select 1 from application_item where status = 'IN_PROGRESS')
                  and exists(select 1 from application_run where profile_id = :id and status like 'PAUSED_%')
                """).param("id", profileId).query(Boolean.class).single();
    }

    public long succeededToday(UUID resumeId) {
        return jdbc.sql("""
                select count(*) from application_item i join application_run r on r.id = i.run_id
                where r.resume_id = :id and i.outcome in ('SENT', 'SENT_LETTER_UNCONFIRMED', 'SENT_LETTER_REJECTED_ALREADY_VIEWED')
                  and i.submission_started_at >= (date_trunc('day', now() at time zone 'Europe/Moscow') at time zone 'Europe/Moscow')
                """).param("id", resumeId).query(Long.class).single();
    }

    public void acknowledge(UUID id, String status) {
        jdbc.sql("update application_item set attention_status = :status where id = :id and status = 'ATTENTION_REQUIRED'")
                .param("id", id).param("status", status).update();
    }

    public int recordTechnicalFailure(UUID runId, boolean failed) {
        return jdbc.sql("""
                update application_run set consecutive_failures = case when :failed then consecutive_failures + 1 else 0 end
                where id = :id returning consecutive_failures
                """).param("id", runId).param("failed", failed).query(Integer.class).single();
    }

    public List<Run> list(UUID resumeId) {
        return jdbc.sql("select * from application_run where resume_id = :id order by sequence desc")
                .param("id", resumeId).query(this::run).list();
    }

    public List<UUID> openAttentionRunIds() {
        return jdbc.sql("""
                select distinct r.id, r.sequence from application_run r
                  join application_item i on i.run_id = r.id
                where i.status = 'ATTENTION_REQUIRED' and i.attention_status = 'OPEN'
                order by r.sequence
                """).query((rs, row) -> rs.getObject("id", UUID.class)).list();
    }

    public List<RunSummary> summaries(UUID resumeId) {
        // No letters or per-item payloads in the history list. Revision invalidates cached details.
        return jdbc.sql("""
                select r.id, r.sequence, r.resume_id, r.resume_name, r.status, r.mode, r.created_at,
                  count(i.id) as total,
                  count(i.id) filter (where i.status not in ('QUEUED', 'IN_PROGRESS')) as processed,
                  count(i.id) filter (where i.status = 'ATTENTION_REQUIRED' and i.attention_status = 'OPEN') as attention,
                  r.active_seconds + case when r.status = 'RUNNING' and r.active_since is not null
                    then greatest(0, floor(extract(epoch from (clock_timestamp() - r.active_since))))::bigint
                    else 0 end as active_seconds,
                  greatest(0, count(i.id) filter (where i.status not in ('QUEUED', 'IN_PROGRESS'))
                    - r.eta_processed_baseline) as timed_processed,
                  md5(r.status || ':' || coalesce(string_agg(
                    i.id::text || ':' || i.status || ':' || i.attention_status || ':' || coalesce(i.outcome, '')
                    || ':' || coalesce(i.letter_source, '') || ':' || coalesce(i.generation_id::text, ''),
                    ',' order by i.position), '')) as revision
                from application_run r left join application_item i on i.run_id = r.id
                where r.resume_id = :id
                group by r.id, r.active_seconds, r.active_since, r.eta_processed_baseline
                order by r.sequence desc
                """).param("id", resumeId).query((rs, n) -> {
            String status = rs.getString("status");
            var estimate = ApplicationRunEstimate.remainingSeconds(status, rs.getLong("total"),
                    rs.getLong("processed"), rs.getLong("active_seconds"), rs.getLong("timed_processed"));
            return new RunSummary(rs.getObject("id", UUID.class), rs.getLong("sequence"),
                    rs.getObject("resume_id", UUID.class), rs.getString("resume_name"), RunStatus.valueOf(status),
                    instant(rs, "created_at"), rs.getLong("total"), rs.getLong("attention"), rs.getString("revision"),
                    estimate.isPresent() ? estimate.getAsLong() : null, RunMode.valueOf(rs.getString("mode")));
        }).list();
    }

    public void create(Start start, UUID accountId, UUID profileId, String hhResumeId, String resumeName, int accountDailyLimit) {
        jdbc.sql("""
                        insert into application_run (id, account_id, resume_id, profile_id, hh_resume_id, resume_name,
                            status, daily_limit, account_daily_limit, pause_from_seconds, pause_to_seconds, cover_letter, skip_questionnaires, mode)
                        values (:id, :account, :resume, :profile, :hash, :name, 'RUNNING', :limit, :cap, :min, :max, :letter, :skip, :mode)
                        """).param("id", start.requestId()).param("account", accountId).param("resume", start.resumeId())
                .param("profile", profileId).param("hash", hhResumeId).param("name", resumeName)
                .param("limit", start.dailyLimit()).param("cap", accountDailyLimit)
                .param("min", start.pauseFromSeconds()).param("max", start.pauseToSeconds())
                .param("letter", start.coverLetter()).param("skip", start.skipQuestionnaires())
                .param("mode", start.mode().name()).update();
    }

    public void add(UUID runId, UUID accountId, UUID vacancyId, String hhId, String title, String company,
                    String url, int page, int position, boolean questionnaire) {
        jdbc.sql("""
                        insert into application_item (id, run_id, vacancy_id, account_id, hh_vacancy_id, vacancy_title,
                            company_name, source_url, search_page, position, has_questionnaire)
                        values (:id, :run, :vacancy, :account, :hh, :title, :company, :url, :page, :position, :questionnaire)
                        """).param("id", UUID.randomUUID()).param("run", runId).param("vacancy", vacancyId)
                .param("account", accountId).param("hh", hhId).param("title", title).param("company", company)
                .param("url", url).param("page", page).param("position", position).param("questionnaire", questionnaire).update();
    }

    public boolean guarded(UUID accountId, String hhId, UUID exceptItem) {
        return jdbc.sql("""
                select exists(select 1 from application_item i join application_run r on r.id = i.run_id
                  where i.account_id = :account and i.hh_vacancy_id = :hh and i.duplicate_guard and i.id <> :except
                    and (i.status <> 'ATTENTION_REQUIRED' or i.outcome is distinct from 'UNKNOWN'
                         or r.status not in ('COMPLETED', 'CANCELLED')))
                """).param("account", accountId).param("hh", hhId).param("except", exceptItem).query(Boolean.class).single();
    }

    /**
     * Called under the application lock when a new, explicitly started run claims this vacancy.
     */
    public void releaseUncertainGuard(UUID accountId, String hhId) {
        jdbc.sql("""
                update application_item i set duplicate_guard = false
                where i.account_id = :account and i.hh_vacancy_id = :hh and i.duplicate_guard
                  and i.status = 'ATTENTION_REQUIRED' and i.outcome = 'UNKNOWN'
                  and exists(select 1 from application_run r where r.id = i.run_id
                    and r.status in ('COMPLETED', 'CANCELLED'))
                """).param("account", accountId).param("hh", hhId).update();
    }

    public void resolvePreviousApplicationAttention(UUID accountId, String hhId) {
        // Keep historical results. Confirming an application resolves its earlier obstacles,
        // but does not prove that a separately submitted letter was delivered.
        jdbc.sql("""
                update application_item set attention_status = 'RESOLVED'
                where account_id = :account and hh_vacancy_id = :hh
                  and status = 'ATTENTION_REQUIRED' and attention_status = 'OPEN'
                  and outcome in ('UNKNOWN', 'NOT_SENT', 'QUESTIONNAIRE', 'LETTER_REQUIRED',
                    'RESUME_UNAVAILABLE', 'RESUME_NOT_FOUND', 'VACANCY_UNAVAILABLE', 'BROWSER_UNAVAILABLE', 'AUTH', 'CAPTCHA')
                """).param("account", accountId).param("hh", hhId).update();
    }

    public long attemptsToday(UUID accountId) {
        return jdbc.sql("""
                select count(*) from application_item where account_id = :id and submission_started_at >=
                  (date_trunc('day', now() at time zone 'Europe/Moscow') at time zone 'Europe/Moscow')
                """).param("id", accountId).query(Long.class).single();
    }

    public Optional<Task> next(UUID profileId) {
        return jdbc.sql("""
                select i.id, r.id as run_id, r.profile_id, r.hh_resume_id, i.hh_vacancy_id, i.source_url, i.search_page, r.cover_letter,
                       r.ai_letter_enabled, r.ai_letter_template, r.ai_letter_max_length, r.resume_plain_text,
                       r.ai_fallback_confirmed, r.generation_attempt, r.mode
                from application_item i join application_run r on r.id = i.run_id
                where r.status = 'RUNNING' and r.profile_id = :profile and r.next_at <= now()
                  and i.status = 'QUEUED'
                  and not exists(select 1 from application_item where status = 'IN_PROGRESS')
                order by i.position limit 1
                """).param("profile", profileId).query((rs, n) -> new Task(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("profile_id", UUID.class), rs.getString("hh_resume_id"), rs.getString("hh_vacancy_id"),
                rs.getString("source_url"), rs.getInt("search_page"), rs.getString("cover_letter"), aiSettings(rs),
                rs.getString("resume_plain_text"), rs.getBoolean("ai_fallback_confirmed"), rs.getInt("generation_attempt"),
                RunMode.valueOf(rs.getString("mode")))).optional();
    }

    public Item item(UUID id) {
        return jdbc.sql("select * from application_item where id = :id").param("id", id).query(ApplicationRepository::item).single();
    }

    public void claim(UUID id) {
        jdbc.sql("update application_item set status = 'IN_PROGRESS', attempted_at = now() where id = :id and status = 'QUEUED'")
                .param("id", id).update();
    }

    public void permit(UUID id) {
        jdbc.sql("update application_item set submission_started_at = now(), duplicate_guard = true where id = :id")
                .param("id", id).update();
    }

    public void finish(UUID id, Outcome outcome, boolean guard) {
        String status = outcome == Outcome.SENT || outcome == Outcome.ALREADY_APPLIED || outcome == Outcome.TEXT_LOADED ? "SUCCEEDED"
                : outcome == Outcome.TEXT_UNAVAILABLE ? "FAILED"
                : outcome == Outcome.VACANCY_ARCHIVED ? "SKIPPED"
                : outcome == Outcome.STOPPED ? "CANCELLED" : "ATTENTION_REQUIRED";
        jdbc.sql("""
                        update application_item set status = :status, outcome = :outcome, duplicate_guard = :guard,
                          has_questionnaire = has_questionnaire or :questionnaire where id = :id
                        """).param("id", id).param("status", status).param("outcome", outcome.name())
                .param("guard", guard).param("questionnaire", outcome == Outcome.QUESTIONNAIRE).update();
    }

    public void retry(UUID id) {
        jdbc.sql("""
                update application_item set status = 'QUEUED', outcome = null, attempted_at = null,
                  submission_started_at = null, duplicate_guard = false, letter_text = null,
                  letter_source = null, letter_error_code = null, generation_id = null
                where id = :id and status = 'IN_PROGRESS'
                """).param("id", id).update();
    }

    public void status(UUID id, RunStatus status) {
        jdbc.sql("""
                        update application_run set status = :status,
                          finished_at = case when :terminal then now() else null end where id = :id
                        """).param("id", id).param("status", status.name())
                .param("terminal", status == RunStatus.COMPLETED || status == RunStatus.CANCELLED).update();
        if (status == RunStatus.CANCELLED) {
            jdbc.sql("update application_item set status = 'CANCELLED', outcome = 'STOPPED' where run_id = :id and status = 'QUEUED'")
                    .param("id", id).update();
        }
    }

    public void advance(UUID id, int seconds) {
        jdbc.sql("update application_run set next_at = now() + :seconds * interval '1 second' where id = :id")
                .param("seconds", seconds).param("id", id).update();
        finishIfIdle(id);
    }

    public void finishIfIdle(UUID id) {
        jdbc.sql("""
                update application_run set status = 'COMPLETED', finished_at = now() where id = :id
                  and status not in ('COMPLETED', 'CANCELLED') and not exists (
                    select 1 from application_item where run_id = :id and status in ('QUEUED', 'IN_PROGRESS'))
                """).param("id", id).update();
    }

    public void recover() {
        // The process may have been offline for an arbitrary time. Count only the active interval up to the
        // last scheduled wake-up; the status trigger will close the remaining near-zero interval below.
        jdbc.sql("""
                update application_run set
                  active_seconds = active_seconds + greatest(0, floor(extract(epoch from
                    (least(clock_timestamp(), greatest(next_at, active_since)) - active_since))))::bigint,
                  active_since = clock_timestamp()
                where status = 'RUNNING' and active_since is not null
                """).update();
        jdbc.sql("""
                update application_item i set status = 'QUEUED', attempted_at = null
                from application_run r where i.run_id = r.id and r.mode = 'DRY_RUN'
                  and i.status = 'IN_PROGRESS' and i.submission_started_at is null
                """).update();
        jdbc.sql("""
                update application_item set status = 'ATTENTION_REQUIRED',
                  outcome = case when submission_started_at is null then 'NOT_SENT' else 'UNKNOWN' end
                where status = 'IN_PROGRESS'
                """).update();
        jdbc.sql("update application_run set status = 'PAUSED_ERROR' where status = 'RUNNING'").update();
        jdbc.sql("""
                update application_run r set status = 'COMPLETED', finished_at = now()
                where status not in ('COMPLETED', 'CANCELLED') and not exists (
                  select 1 from application_item i where i.run_id = r.id and i.status in ('QUEUED', 'IN_PROGRESS'))
                """).update();
    }

    private Run run(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        var items = jdbc.sql("select * from application_item where run_id = :id order by position")
                .param("id", id).query(ApplicationRepository::item).list();
        return new Run(id, rs.getLong("sequence"), rs.getObject("account_id", UUID.class), rs.getObject("resume_id", UUID.class),
                rs.getObject("profile_id", UUID.class), rs.getString("hh_resume_id"), rs.getString("resume_name"),
                RunStatus.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "finished_at"),
                rs.getInt("daily_limit"), rs.getInt("account_daily_limit"), rs.getInt("pause_from_seconds"),
                rs.getInt("pause_to_seconds"), rs.getString("cover_letter"), rs.getBoolean("skip_questionnaires"), items, aiSettings(rs),
                instant(rs, "ai_limit_resets_at"), rs.getBoolean("ai_fallback_confirmed"), RunMode.valueOf(rs.getString("mode")));
    }

    public void snapshotLetterSettings(UUID runId, org.hhclicker.backend.resume.model.AiLetterSettings ai, String resumeText) {
        jdbc.sql("update application_run set ai_letter_enabled=:enabled, ai_letter_template=:template, ai_letter_max_length=:max, resume_plain_text=:text where id=:id")
                .param("id", runId).param("enabled", ai.enabled()).param("template", ai.template())
                .param("max", ai.maxLength()).param("text", ai.enabled() ? resumeText : null).update();
    }

    public void prepareLetter(UUID itemId, PreparedLetter letter) {
        jdbc.sql("update application_item set letter_text=:text, letter_source=:source, letter_error_code=:error, generation_id=:generation where id=:id")
                .param("id", itemId).param("text", letter.text()).param("source", letter.source())
                .param("error", letter.errorCode()).param("generation", letter.generationId()).update();
    }

    public void pauseAiLimit(UUID itemId, UUID runId, Long resetsAt) {
        jdbc.sql("update application_item set status='QUEUED', attempted_at=null where id=:id and submission_started_at is null")
                .param("id", itemId).update();
        jdbc.sql("update application_run set status='PAUSED_AI_LIMIT', ai_limit_resets_at=:reset where id=:id")
                .param("id", runId).param("reset", resetsAt == null ? null : java.sql.Timestamp.from(Instant.ofEpochSecond(resetsAt))).update();
    }

    public void resumeAiLimit(UUID runId, boolean confirmFallback) {
        jdbc.sql("update application_run set ai_fallback_confirmed=:confirmed, ai_limit_resets_at=null, generation_attempt=generation_attempt+1 where id=:id")
                .param("id", runId).param("confirmed", confirmFallback).update();
    }
}
