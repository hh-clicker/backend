package org.hhclicker.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * Минимальный набор синтетических данных, необходимый модулю {@code application}:
 * HH-аккаунт, резюме, браузерный профиль, поиск и вакансии со связью с поиском.
 * <p>
 * Никаких обращений к HH, Browser Agent или Generation Agent: только строки в тестовой БД.
 */
public final class ApplicationDatabaseFixtures {

    public static final String HH_RESUME_ID = "abcdef0123456789abcd";

    private final JdbcClient jdbc;

    public ApplicationDatabaseFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        jdbc.sql("""
                truncate table application_item, application_run, vacancy_label, vacancy_search_link,
                    vacancy_favorite, employer_favorite, company_ban, vacancy, vacancy_search,
                    browser_profile, hh_resume, hh_account
                restart identity cascade
                """).update();
    }

    public Workspace workspace() {
        return workspace("Основной аккаунт", HH_RESUME_ID, 20);
    }

    public Workspace workspace(String accountName, String hhResumeId, int dailyLimit) {
        UUID accountId = account(accountName);
        UUID resumeId = resume(accountId, hhResumeId, dailyLimit);
        UUID profileId = profile(accountId);
        UUID searchId = search(resumeId, hhResumeId);
        return new Workspace(accountId, resumeId, profileId, searchId, hhResumeId);
    }

    public UUID account(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into hh_account (id, name, hh_user_id, authorization_status, created_at, updated_at)
                values (:id, :name, null, 'AUTHORIZED', now(), now())
                """).param("id", id).param("name", name).update();
        return id;
    }

    public UUID resume(UUID accountId, String hhResumeId, int dailyLimit) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into hh_resume (id, account_id, hh_resume_id, name, cover_letter, daily_limit,
                            pause_from_seconds, pause_to_seconds, skip_questionnaires, created_at, updated_at,
                            plain_text, text_loaded_at, ai_letter_enabled, ai_letter_template, ai_letter_max_length)
                        values (:id, :account, :hash, 'Java-разработчик', '', :limit, 10, 10, true, now(), now(),
                            'Синтетический текст резюме', now(), false, null, 1500)
                        """).param("id", id).param("account", accountId).param("hash", hhResumeId)
                .param("limit", dailyLimit).update();
        return id;
    }

    public UUID profile(UUID accountId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into browser_profile (id, account_id, created_at, updated_at) values (:id, :account, now(), now())")
                .param("id", id).param("account", accountId).update();
        return id;
    }

    public UUID search(UUID resumeId, String seed) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into vacancy_search (id, resume_id, name, source_url, source_hash, pages_limit,
                    status, new_vacancies, total_vacancies, created_at, updated_at)
                values (:id, :resume, 'Синтетический поиск', 'https://hh.ru/search/vacancy?page=0',
                    md5(:seed) || md5(:seed), 1, 'ACTIVE', 0, 0, now(), now())
                """).param("id", id).param("resume", resumeId).param("seed", seed).update();
        return id;
    }

    /**
     * Вакансия в статусе NEW, поставленная пользователем в очередь и связанная с поиском.
     */
    public UUID queuedVacancy(Workspace workspace, String hhVacancyId, int position) {
        UUID id = vacancy(workspace.resumeId(), hhVacancyId, "NEW", true);
        link(id, workspace.searchId(), position);
        return id;
    }

    public UUID vacancy(UUID resumeId, String hhVacancyId, String status, boolean queued) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into vacancy (id, resume_id, hh_vacancy_id, title, employer_id, company_name,
                            location, work_format, experience, salary_text, has_questionnaire, status, ban_scope,
                            queued_for_application, first_seen_at, last_seen_at, created_at, updated_at)
                        values (:id, :resume, :hh, 'Java-разработчик', '3529', 'Синтетическая компания',
                            '', '', '', '', false, :status, null, :queued, now(), now(), now(), now())
                        """).param("id", id).param("resume", resumeId).param("hh", hhVacancyId)
                .param("status", status).param("queued", queued).update();
        return id;
    }

    public void link(UUID vacancyId, UUID searchId, int position) {
        jdbc.sql("""
                        insert into vacancy_search_link (id, vacancy_id, search_id, position, created_at, updated_at)
                        values (:id, :vacancy, :search, :position, now(), now())
                        """).param("id", UUID.randomUUID()).param("vacancy", vacancyId)
                .param("search", searchId).param("position", position).update();
    }

    public void enableAiLetter(UUID resumeId, String template, int maxLength) {
        jdbc.sql("""
                update hh_resume set ai_letter_enabled = true, ai_letter_template = :template,
                    ai_letter_max_length = :max where id = :id
                """).param("id", resumeId).param("template", template).param("max", maxLength).update();
    }

    public String itemStatus(UUID itemId) {
        return jdbc.sql("select status from application_item where id = :id").param("id", itemId)
                .query(String.class).single();
    }

    public String itemOutcome(UUID itemId) {
        return jdbc.sql("select coalesce(outcome, '') from application_item where id = :id").param("id", itemId)
                .query(String.class).single();
    }

    public boolean itemGuarded(UUID itemId) {
        return jdbc.sql("select duplicate_guard from application_item where id = :id").param("id", itemId)
                .query(Boolean.class).single();
    }

    public String runStatus(UUID runId) {
        return jdbc.sql("select status from application_run where id = :id").param("id", runId)
                .query(String.class).single();
    }

    public long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    /**
     * Смещение относительно полуночи Europe/Moscow считается самой БД: тест не зависит
     * от часового пояса машины и от момента запуска.
     */
    public void setSubmissionRelativeToMoscowMidnight(UUID itemId, String interval) {
        jdbc.sql("""
                update application_item set submission_started_at =
                    (date_trunc('day', now() at time zone 'Europe/Moscow') at time zone 'Europe/Moscow')
                    + cast(:interval as interval)
                where id = :id
                """).param("id", itemId).param("interval", interval).update();
    }

    /**
     * Рабочее пространство одного HH-аккаунта: резюме, браузерный профиль и сохранённый поиск.
     */
    public record Workspace(UUID accountId, UUID resumeId, UUID profileId, UUID searchId, String hhResumeId) {
    }
}
