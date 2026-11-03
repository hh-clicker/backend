package org.hhclicker.backend.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.model.NotificationSettings;
import org.hhclicker.backend.settings.repository.NotificationRepository;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Настоящие PostgreSQL-ограничения singleton-настроек и компактная SQL-проекция запусков.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationRepositoryIT extends PostgresIntegrationTest {

    private static final OffsetDateTime NEXT_AT = OffsetDateTime.parse("2026-09-04T09:00:00Z");
    private static final OffsetDateTime CHANGED_NEXT_AT = OffsetDateTime.parse("2026-09-04T10:30:00Z");
    private static final NotificationSettings DEFAULTS = new NotificationSettings(true, true, true, true, true, true);

    @Autowired
    private NotificationRepository repository;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        new ApplicationDatabaseFixtures(jdbc).clean();
        restoreSingleton();
    }

    @Test
    void migrationProvidesOneSingletonRowAndTrueDefaultsForEveryPreference() {
        assertEquals(1, count("notification_settings"));
        assertEquals(DEFAULTS, repository.settings());
        long trueDefaults = jdbc.sql("""
                select count(*) from information_schema.columns
                where table_schema = 'hhclicker' and table_name = 'notification_settings'
                  and column_name in ('desktop_enabled', 'sound_enabled', 'notify_run_completed',
                    'notify_run_paused', 'notify_attention_required', 'notify_relevance_completed',
                    'notify_market_completed')
                  and lower(column_default) like '%true%'
                """).query(Long.class).single();
        assertEquals(7, trueDefaults, "каждая настройка должна иметь true как SQL default");
    }

    @Test
    void settingsReadsAllFiveColumnsInTheirCorrectPositions() {
        jdbc.sql("""
                update notification_settings set desktop_enabled = false, sound_enabled = true,
                  notify_run_completed = false, notify_run_paused = true,
                  notify_attention_required = false where id = 1
                """).update();

        assertEquals(new NotificationSettings(false, true, false, true, false, true), repository.settings());
    }

    @Test
    void saveRoundTripsAnArbitraryCombinationIncludingDisabledValues() {
        NotificationSettings settings = new NotificationSettings(false, false, false, false, false, false, false);

        repository.save(settings);

        assertEquals(settings, repository.settings());
    }

    @Test
    void repeatedSaveReplacesTheSingletonWithoutAddingRows() {
        repository.save(new NotificationSettings(false, true, false, true, false, true));
        NotificationSettings replacement = new NotificationSettings(true, false, true, false, true, false);

        repository.save(replacement);

        assertEquals(replacement, repository.settings());
        assertEquals(1, count("notification_settings"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "desktop_enabled", "sound_enabled", "notify_run_completed", "notify_run_paused",
            "notify_attention_required", "notify_relevance_completed", "notify_market_completed"
    })
    void everyPreferenceColumnRejectsNullWithoutChangingTheSingleton(String column) {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.sql("update notification_settings set " + column + " = null where id = 1").update());

        assertEquals(DEFAULTS, repository.settings());
        assertEquals(1, count("notification_settings"));
    }

    @Test
    void idCheckRejectsASecondLogicalSettingsRowAndLeavesSingletonIntact() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.sql("insert into notification_settings (id) values (2)").update());

        assertEquals(1, count("notification_settings"));
        assertEquals(DEFAULTS, repository.settings());
    }

    @Test
    void saveSilentlyUpdatesNothingWhenTheAssumedSingletonRowIsMissing() {
        try {
            jdbc.sql("delete from notification_settings where id = 1").update();

            repository.save(new NotificationSettings(false, false, false, false, false, false));

            assertEquals(0, count("notification_settings"),
                    "save не вставляет и не проверяет число обновлённых строк");
        } finally {
            restoreSingleton();
        }
    }

    @Test
    void runsReturnsAnEmptyListWhenThereIsNoApplicationHistory() {
        assertTrue(repository.runs().isEmpty());
    }

    @Test
    void runProjectionCarriesIdentityCountsStringStatusesAndOrderedOpenAttentionIds() {
        Workspace workspace = insertWorkspace(1);
        UUID runId = runId(10);
        insertRun(runId, 10, workspace, "PAUSED_AUTH", NEXT_AT);
        UUID succeeded = insertItem(1, runId, workspace, 4, "SUCCEEDED", "OPEN", "NOT_SENT");
        UUID failed = insertItem(2, runId, workspace, 3, "FAILED", "OPEN", "SENT");
        UUID laterOpenAttention = insertItem(3, runId, workspace, 2, "ATTENTION_REQUIRED", "OPEN", "QUESTIONNAIRE");
        insertItem(4, runId, workspace, 1, "ATTENTION_REQUIRED", "RESOLVED", "QUESTIONNAIRE");
        UUID firstOpenAttention = insertItem(5, runId, workspace, 0, "ATTENTION_REQUIRED", "OPEN", "QUESTIONNAIRE");
        insertItem(6, runId, workspace, 5, "QUEUED", "OPEN", "NOT_SENT");
        insertItem(7, runId, workspace, 6, "CANCELLED", "OPEN", "STOPPED");
        jdbc.sql("update application_run set active_seconds = 600, eta_processed_baseline = 2 where id = :id")
                .param("id", runId).update();

        NotificationRun run = repository.runs().getFirst();

        assertEquals(runId, run.id());
        assertEquals(workspace.accountId(), run.accountId());
        assertEquals(workspace.resumeId(), run.resumeId());
        assertEquals("Резюме 1", run.resumeName());
        assertEquals(10, run.sequence());
        assertEquals("PAUSED_AUTH", run.status());
        assertEquals(revisionOf(runId), run.revision());
        assertEquals(7, run.total());
        assertEquals(1, run.succeeded(), "outcome не должен влиять на счётчик строкового status SUCCEEDED");
        assertEquals(1, run.failed(), "outcome не должен влиять на счётчик строкового status FAILED");
        assertEquals(6, run.processed(), "обработанным считается любой статус, кроме QUEUED и IN_PROGRESS");
        assertEquals(600, run.activeSeconds());
        assertEquals(4, run.timedProcessed(), "старые элементы до начала замера не должны искажать ETA");
        assertEquals(List.of(firstOpenAttention.toString(), laterOpenAttention.toString()), run.attentionIds());
        assertTrue(run.attentionIds().stream().noneMatch(id -> id.equals(succeeded.toString()) || id.equals(failed.toString())));
    }

    @Test
    void runWithoutItemsHasZeroCountersAndARealEmptyAttentionList() {
        Workspace workspace = insertWorkspace(1);
        insertRun(runId(1), 1, workspace, "COMPLETED", NEXT_AT);

        NotificationRun run = repository.runs().getFirst();

        assertEquals(0, run.total());
        assertEquals(0, run.succeeded());
        assertEquals(0, run.failed());
        assertEquals(0, run.processed());
        assertEquals(0, run.activeSeconds());
        assertEquals(0, run.timedProcessed());
        assertEquals(List.of(), run.attentionIds());
    }

    @Test
    void runningProjectionIncludesTheCurrentActiveInterval() {
        Workspace workspace = insertWorkspace(1);
        UUID runId = runId(1);
        insertRun(runId, 1, workspace, "RUNNING", NEXT_AT);
        insertItem(1, runId, workspace, 0, "SUCCEEDED", "OPEN", "SENT");
        jdbc.sql("""
                update application_run set active_seconds = 60,
                  active_since = clock_timestamp() - interval '120 seconds' where id = :id
                """).param("id", runId).update();

        NotificationRun run = repository.runs().getFirst();

        assertTrue(run.activeSeconds() >= 179 && run.activeSeconds() <= 181,
                "проекция должна прибавлять текущий RUNNING-интервал");
        assertEquals(1, run.timedProcessed());
    }

    @Test
    void runsKeepsTheGlobalLatestHundredThenReturnsThemInAscendingSequenceAcrossAccounts() {
        Workspace first = insertWorkspace(1);
        Workspace second = insertWorkspace(2);
        for (long sequence = 1; sequence <= 101; sequence++) {
            Workspace owner = sequence % 2 == 0 ? first : second;
            insertRun(runId(sequence), sequence, owner, "COMPLETED", NEXT_AT.plusSeconds(sequence));
        }

        List<NotificationRun> runs = repository.runs();

        assertEquals(100, runs.size(), "лимит должен быть глобальным, а не отдельным для каждого аккаунта");
        assertEquals(LongStream.rangeClosed(2, 101).boxed().toList(),
                runs.stream().map(NotificationRun::sequence).toList());
        assertTrue(runs.stream().noneMatch(run -> run.id().equals(runId(1))));
        for (NotificationRun run : runs) {
            Workspace expectedOwner = run.sequence() % 2 == 0 ? first : second;
            assertEquals(expectedOwner.accountId(), run.accountId());
            assertEquals(expectedOwner.resumeId(), run.resumeId());
        }
    }

    @Test
    void revisionTracksOnlyRunNextAtAndIgnoresAnItemStatusChange() {
        Workspace workspace = insertWorkspace(1);
        UUID runId = runId(1);
        insertRun(runId, 1, workspace, "COMPLETED", NEXT_AT);
        UUID itemId = insertItem(1, runId, workspace, 0, "QUEUED", "OPEN", "NOT_SENT");
        String initial = repository.runs().getFirst().revision();
        assertEquals(revisionOf(runId), initial);

        jdbc.sql("update application_item set status = 'SUCCEEDED' where id = :id")
                .param("id", itemId).update();
        assertEquals(initial, repository.runs().getFirst().revision(),
                "изменение только item не меняет основанный на next_at revision");

        jdbc.sql("update application_run set next_at = :next where id = :id")
                .param("next", CHANGED_NEXT_AT).param("id", runId).update();
        String changed = repository.runs().getFirst().revision();
        assertEquals(revisionOf(runId), changed);
        assertNotEquals(initial, changed);
    }

    private void restoreSingleton() {
        jdbc.sql("""
                insert into notification_settings (id, desktop_enabled, sound_enabled, notify_run_completed,
                  notify_run_paused, notify_attention_required, notify_relevance_completed,
                  notify_market_completed) values (1, true, true, true, true, true, true, true)
                on conflict (id) do update set desktop_enabled = true, sound_enabled = true,
                  notify_run_completed = true, notify_run_paused = true, notify_attention_required = true,
                  notify_relevance_completed = true, notify_market_completed = true
                """).update();
    }

    private Workspace insertWorkspace(int number) {
        UUID accountId = uuid("10000000", number);
        UUID resumeId = uuid("20000000", number);
        UUID profileId = uuid("50000000", number);
        jdbc.sql("""
                insert into hh_account (id, name, authorization_status, created_at, updated_at)
                values (:id, :name, 'AUTHORIZED', :now, :now)
                """).param("id", accountId).param("name", "Аккаунт " + number).param("now", NEXT_AT).update();
        jdbc.sql("""
                        insert into hh_resume (id, account_id, hh_resume_id, name, cover_letter, daily_limit,
                          pause_from_seconds, pause_to_seconds, skip_questionnaires, created_at, updated_at)
                        values (:id, :account, :hh, :name, '', 20, 30, 90, true, :now, :now)
                        """).param("id", resumeId).param("account", accountId)
                .param("hh", "%020d".formatted(number)).param("name", "Резюме " + number)
                .param("now", NEXT_AT).update();
        jdbc.sql("""
                insert into browser_profile (id, account_id, created_at, updated_at)
                values (:id, :account, :now, :now)
                """).param("id", profileId).param("account", accountId).param("now", NEXT_AT).update();
        return new Workspace(accountId, resumeId, profileId);
    }

    private void insertRun(UUID id, long sequence, Workspace workspace, String status, OffsetDateTime nextAt) {
        jdbc.sql("""
                        insert into application_run (id, sequence, account_id, resume_id, profile_id, hh_resume_id,
                          resume_name, status, created_at, next_at, daily_limit, account_daily_limit,
                          pause_from_seconds, pause_to_seconds, cover_letter, skip_questionnaires)
                        values (:id, :sequence, :account, :resume, :profile, :hhResume, :resumeName, :status,
                          :created, :next, 20, 20, 30, 90, '', true)
                        """).param("id", id).param("sequence", sequence).param("account", workspace.accountId())
                .param("resume", workspace.resumeId()).param("profile", workspace.profileId())
                .param("hhResume", workspace.hhResumeId()).param("resumeName", workspace.resumeName())
                .param("status", status).param("created", NEXT_AT.minusSeconds(sequence)).param("next", nextAt).update();
    }

    private UUID insertItem(int number, UUID runId, Workspace workspace, int position, String status,
                            String attentionStatus, String outcome) {
        UUID vacancyId = uuid("70000000", number);
        UUID itemId = uuid("60000000", number);
        String hhVacancyId = "vacancy-" + number;
        jdbc.sql("""
                        insert into vacancy (id, resume_id, hh_vacancy_id, title, employer_id, company_name,
                          location, work_format, experience, salary_text, has_questionnaire, status, ban_scope,
                          queued_for_application, first_seen_at, last_seen_at, created_at, updated_at)
                        values (:id, :resume, :hh, :title, 'employer', 'Компания', '', '', '', '', false,
                          'NEW', null, false, :now, :now, :now, :now)
                        """).param("id", vacancyId).param("resume", workspace.resumeId()).param("hh", hhVacancyId)
                .param("title", "Вакансия " + number).param("now", NEXT_AT).update();
        jdbc.sql("""
                        insert into application_item (id, run_id, vacancy_id, account_id, hh_vacancy_id,
                          vacancy_title, company_name, source_url, search_page, position, status, outcome,
                          attention_status, duplicate_guard, has_questionnaire)
                        values (:id, :run, :vacancy, :account, :hh, :title, 'Компания', 'https://hh.ru/vacancy',
                          0, :position, :status, :outcome, :attention, false, false)
                        """).param("id", itemId).param("run", runId).param("vacancy", vacancyId)
                .param("account", workspace.accountId()).param("hh", hhVacancyId)
                .param("title", "Вакансия " + number).param("position", position).param("status", status)
                .param("outcome", outcome).param("attention", attentionStatus).update();
        return itemId;
    }

    private String revisionOf(UUID runId) {
        return jdbc.sql("select next_at::text from application_run where id = :id")
                .param("id", runId).query(String.class).single();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private UUID runId(long sequence) {
        return uuid("30000000", sequence);
    }

    private UUID uuid(String prefix, long suffix) {
        return UUID.fromString(prefix + "-0000-0000-0000-%012d".formatted(suffix));
    }

    private record Workspace(UUID accountId, UUID resumeId, UUID profileId) {
        String hhResumeId() {
            return "%020d".formatted(Long.parseLong(resumeId.toString().substring(24)));
        }

        String resumeName() {
            return "Резюме " + Long.parseLong(resumeId.toString().substring(24));
        }
    }
}
