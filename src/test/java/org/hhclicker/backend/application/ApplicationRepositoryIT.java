package org.hhclicker.backend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.Start;
import org.hhclicker.backend.application.repository.ApplicationRepository;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Настоящий SQL, ограничения и индексы PostgreSQL. Схему готовит тот же Liquibase-changelog,
 * что и в production, поэтому тест заодно проверяет применимость миграций на чистой БД.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationRepositoryIT extends PostgresIntegrationTest {

    private static final String SOURCE_URL = "https://hh.ru/search/vacancy?page=0";

    @Autowired
    private ApplicationRepository repository;
    @Autowired
    private JdbcClient jdbc;

    private ApplicationDatabaseFixtures fixtures;
    private ApplicationDatabaseFixtures.Workspace workspace;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        workspace = fixtures.workspace();
    }

    @Test
    void liquibaseCreatesApplicationTablesWithPartialIndexes() {
        assertEquals(0, fixtures.count("application_run"));
        assertEquals(0, fixtures.count("application_item"));
        long indexes = jdbc.sql("""
                select count(*) from pg_indexes where schemaname = 'hhclicker'
                  and indexname in ('application_one_active_run', 'application_no_duplicate_submission')
                """).query(Long.class).single();
        assertEquals(2, indexes, "частичные уникальные индексы модуля application не созданы");
    }

    @Test
    void onlyOneUnfinishedRunIsAllowedForTheWholeInstallation() {
        UUID first = createRun(List.of(vacancy("10000001", 0)));

        assertThrows(DuplicateKeyException.class, () -> createRun(List.of(vacancy("10000002", 1))));

        repository.status(first, RunStatus.COMPLETED);
        UUID second = createRun(List.of(vacancy("10000003", 2)));
        assertTrue(repository.exists(second));
    }

    @Test
    void duplicateSubmissionGuardIsEnforcedByThePartialUniqueIndex() {
        UUID otherResume = fixtures.resume(workspace.accountId(), "0123456789abcdef0123", 20);
        UUID first = vacancy("20000001", 0);
        UUID second = fixtures.vacancy(otherResume, "20000001", "NEW", true);
        UUID runId = createRun(List.of(first));
        repository.add(runId, workspace.accountId(), second, "20000001", "Java-разработчик",
                "Синтетическая компания", SOURCE_URL, 0, 1, false);
        List<UUID> items = itemIds(runId);

        repository.permit(items.getFirst());

        assertThrows(DuplicateKeyException.class, () -> repository.permit(items.get(1)));
    }

    @Test
    void nextReturnsTheFirstQueuedItemOfTheRunningRunOnly() {
        UUID runId = createRun(List.of(vacancy("30000001", 0), vacancy("30000002", 1)));

        var task = repository.next(workspace.profileId());

        assertTrue(task.isPresent());
        assertEquals(runId, task.get().runId());
        assertEquals("30000001", task.get().hhVacancyId());
        assertEquals(workspace.hhResumeId(), task.get().hhResumeId());
        assertEquals("Обычное письмо", task.get().coverLetter());

        repository.status(runId, RunStatus.PAUSED_USER);
        assertTrue(repository.next(workspace.profileId()).isEmpty(), "приостановленный запуск не выдаёт задачи");
    }

    @Test
    void nextRespectsTheScheduledPauseBetweenItems() {
        UUID runId = createRun(List.of(vacancy("31000001", 0), vacancy("31000002", 1)));

        repository.advance(runId, 3600);
        assertTrue(repository.next(workspace.profileId()).isEmpty(), "пауза между откликами не соблюдена");

        repository.advance(runId, 0);
        assertTrue(repository.next(workspace.profileId()).isPresent());
    }

    @Test
    void activeTimeAccumulatesOnlyWhileTheRunIsRunning() {
        UUID runId = createRun(List.of(vacancy("31500001", 0)));
        jdbc.sql("update application_run set active_since = clock_timestamp() - interval '120 seconds' where id = :id")
                .param("id", runId).update();

        repository.status(runId, RunStatus.PAUSED_USER);

        long firstInterval = activeSeconds(runId);
        assertTrue(firstInterval >= 119 && firstInterval <= 121);
        assertNull(activeSince(runId));

        repository.status(runId, RunStatus.RUNNING);
        assertNotNull(activeSince(runId));
        jdbc.sql("update application_run set active_since = clock_timestamp() - interval '30 seconds' where id = :id")
                .param("id", runId).update();
        repository.status(runId, RunStatus.PAUSED_ERROR);

        long total = activeSeconds(runId);
        assertTrue(total >= firstInterval + 29 && total <= firstInterval + 31,
                "время между двумя активными интервалами не должно попадать в накопитель");
    }

    @Test
    void nextIsBlockedWhileAnotherItemIsStillInProgress() {
        UUID runId = createRun(List.of(vacancy("32000001", 0), vacancy("32000002", 1)));
        repository.claim(itemIds(runId).getFirst());

        assertTrue(repository.next(workspace.profileId()).isEmpty());
    }

    @Test
    void nextCarriesTheRunSnapshotOfLetterSettingsAndResumeText() {
        UUID runId = createRun(List.of(vacancy("33000001", 0)));
        var ai = new AiLetterSettings(true, "Здравствуйте!\n#AI_CHANGE#\nСпасибо!", 1200);
        repository.snapshotLetterSettings(runId, ai, "Синтетический текст резюме");

        var task = repository.next(workspace.profileId()).orElseThrow();

        assertEquals(ai, task.aiLetter());
        assertEquals("Синтетический текст резюме", task.resumePlainText());
    }

    @Test
    void disabledGenerationDoesNotStoreTheResumeTextInTheRunSnapshot() {
        UUID runId = createRun(List.of(vacancy("34000001", 0)));

        repository.snapshotLetterSettings(runId, AiLetterSettings.defaults(), "Синтетический текст резюме");

        assertNull(repository.next(workspace.profileId()).orElseThrow().resumePlainText());
    }

    @Test
    void claimMovesOnlyQueuedItemsIntoProgress() {
        UUID runId = createRun(List.of(vacancy("40000001", 0)));
        UUID itemId = itemIds(runId).getFirst();

        repository.claim(itemId);
        assertEquals("IN_PROGRESS", fixtures.itemStatus(itemId));
        assertNotNull(repository.item(itemId).attemptedAt());

        repository.finish(itemId, Outcome.NOT_SENT, false);
        repository.claim(itemId);
        assertEquals("ATTENTION_REQUIRED", fixtures.itemStatus(itemId), "завершённый item нельзя вернуть в работу");
    }

    @Test
    void permitStartsSubmissionAndRaisesTheDuplicateGuard() {
        UUID runId = createRun(List.of(vacancy("41000001", 0)));
        UUID itemId = itemIds(runId).getFirst();
        repository.claim(itemId);

        assertNull(repository.item(itemId).submissionStartedAt());
        repository.permit(itemId);

        assertNotNull(repository.item(itemId).submissionStartedAt());
        assertTrue(fixtures.itemGuarded(itemId));
    }

    @Test
    void retryClearsTheProvisionalPermitAndReturnsTheItemToTheQueue() {
        UUID runId = createRun(List.of(vacancy("41000002", 0)));
        UUID itemId = itemIds(runId).getFirst();
        repository.claim(itemId);
        repository.permit(itemId);

        repository.retry(itemId);

        var item = repository.item(itemId);
        assertEquals("QUEUED", item.status());
        assertNull(item.outcome());
        assertNull(item.attemptedAt());
        assertNull(item.submissionStartedAt());
        assertFalse(fixtures.itemGuarded(itemId));
    }

    @ParameterizedTest
    @CsvSource({
            "SENT, SUCCEEDED",
            "ALREADY_APPLIED, SUCCEEDED",
            "STOPPED, CANCELLED",
            "SENT_LETTER_UNCONFIRMED, ATTENTION_REQUIRED",
            "SENT_LETTER_REJECTED_ALREADY_VIEWED, ATTENTION_REQUIRED",
            "UNKNOWN, ATTENTION_REQUIRED",
            "QUESTIONNAIRE, ATTENTION_REQUIRED",
            "NOT_SENT, ATTENTION_REQUIRED"
    })
    void finishMapsTheOutcomeToTheItemStatus(Outcome outcome, String expectedStatus) {
        UUID runId = createRun(List.of(vacancy("42000001", 0)));
        UUID itemId = itemIds(runId).getFirst();
        repository.claim(itemId);

        repository.finish(itemId, outcome, outcome.applied());

        assertEquals(expectedStatus, fixtures.itemStatus(itemId));
        assertEquals(outcome.name(), fixtures.itemOutcome(itemId));
        assertEquals(outcome.applied(), fixtures.itemGuarded(itemId));
    }

    @Test
    void questionnaireFlagIsStickyAcrossLaterResults() {
        UUID runId = createRun(List.of(vacancy("43000001", 0)));
        UUID itemId = itemIds(runId).getFirst();
        repository.claim(itemId);

        repository.finish(itemId, Outcome.QUESTIONNAIRE, false);
        assertTrue(repository.item(itemId).hasQuestionnaire());

        repository.finish(itemId, Outcome.NOT_SENT, false);
        assertTrue(repository.item(itemId).hasQuestionnaire(), "признак анкеты не должен сбрасываться");
    }

    @Test
    void finishIfIdleCompletesTheRunOnlyWhenNoWorkRemains() {
        UUID runId = createRun(List.of(vacancy("44000001", 0), vacancy("44000002", 1)));
        List<UUID> items = itemIds(runId);

        repository.finish(items.getFirst(), Outcome.SENT, true);
        repository.finishIfIdle(runId);
        assertEquals("RUNNING", fixtures.runStatus(runId));

        repository.finish(items.get(1), Outcome.NOT_SENT, false);
        repository.finishIfIdle(runId);
        assertEquals("COMPLETED", fixtures.runStatus(runId));
    }

    @Test
    void cancellingTheRunAlsoCancelsItsQueuedItems() {
        UUID runId = createRun(List.of(vacancy("45000001", 0), vacancy("45000002", 1)));
        List<UUID> items = itemIds(runId);
        repository.claim(items.getFirst());
        repository.finish(items.getFirst(), Outcome.SENT, true);

        repository.status(runId, RunStatus.CANCELLED);

        assertEquals("SUCCEEDED", fixtures.itemStatus(items.getFirst()));
        assertEquals("CANCELLED", fixtures.itemStatus(items.get(1)));
        assertEquals("STOPPED", fixtures.itemOutcome(items.get(1)));
        assertNotNull(repository.find(runId).orElseThrow().finishedAt());
    }

    @Test
    void recoverTurnsInterruptedAttemptsIntoTasksAndPausesTheRun() {
        UUID runId = createRun(List.of(vacancy("46000001", 0), vacancy("46000002", 1), vacancy("46000003", 2)));
        List<UUID> items = itemIds(runId);
        repository.claim(items.getFirst());
        repository.claim(items.get(1));
        repository.permit(items.get(1));

        repository.recover();

        assertEquals("ATTENTION_REQUIRED", fixtures.itemStatus(items.getFirst()));
        assertEquals("NOT_SENT", fixtures.itemOutcome(items.getFirst()));
        assertEquals("ATTENTION_REQUIRED", fixtures.itemStatus(items.get(1)));
        assertEquals("UNKNOWN", fixtures.itemOutcome(items.get(1)), "выданное разрешение делает результат неопределённым");
        assertEquals("PAUSED_ERROR", fixtures.runStatus(runId));
    }

    @Test
    void recoverCompletesTheRunWhenNothingIsLeftInTheQueue() {
        UUID runId = createRun(List.of(vacancy("47000001", 0)));
        repository.claim(itemIds(runId).getFirst());

        repository.recover();

        assertEquals("COMPLETED", fixtures.runStatus(runId));
        assertNotNull(repository.find(runId).orElseThrow().finishedAt());
    }

    @Test
    void dailyBudgetIsCountedFromMidnightInMoscow() {
        UUID runId = createRun(List.of(vacancy("50000001", 0), vacancy("50000002", 1)));
        List<UUID> items = itemIds(runId);
        fixtures.setSubmissionRelativeToMoscowMidnight(items.getFirst(), "-1 second");
        fixtures.setSubmissionRelativeToMoscowMidnight(items.get(1), "1 second");

        assertEquals(1, repository.attemptsToday(workspace.accountId()),
                "попытка до полуночи Europe/Moscow не относится к сегодняшнему бюджету");
    }

    @Test
    void confirmedApplicationsOfTodayAreCountedFromMidnightInMoscow() {
        UUID runId = createRun(List.of(vacancy("51000001", 0), vacancy("51000002", 1), vacancy("51000003", 2)));
        List<UUID> items = itemIds(runId);
        repository.finish(items.getFirst(), Outcome.SENT, true);
        repository.finish(items.get(1), Outcome.SENT, false);
        repository.finish(items.get(2), Outcome.UNKNOWN, false);
        fixtures.setSubmissionRelativeToMoscowMidnight(items.getFirst(), "1 second");
        fixtures.setSubmissionRelativeToMoscowMidnight(items.get(1), "-1 second");
        fixtures.setSubmissionRelativeToMoscowMidnight(items.get(2), "1 second");

        assertEquals(1, repository.succeededToday(workspace.resumeId()));
    }

    @Test
    void guardIgnoresTheCurrentItemAndReleasableHistory() {
        UUID runId = createRun(List.of(vacancy("60000001", 0)));
        UUID itemId = itemIds(runId).getFirst();
        repository.claim(itemId);
        repository.permit(itemId);
        repository.finish(itemId, Outcome.UNKNOWN, true);

        assertTrue(repository.guarded(workspace.accountId(), "60000001", UUID.randomUUID()),
                "неопределённая попытка активного запуска продолжает защищать вакансию");
        assertFalse(repository.guarded(workspace.accountId(), "60000001", itemId),
                "сама попытка не защищает себя от себя же");

        repository.status(runId, RunStatus.COMPLETED);
        assertFalse(repository.guarded(workspace.accountId(), "60000001", UUID.randomUUID()),
                "после завершения запуска неопределённую попытку можно повторить");
    }

    @Test
    void confirmedApplicationKeepsGuardEvenAfterTheRunIsFinished() {
        UUID runId = createRun(List.of(vacancy("61000001", 0)));
        UUID itemId = itemIds(runId).getFirst();
        repository.claim(itemId);
        repository.permit(itemId);
        repository.finish(itemId, Outcome.SENT, true);
        repository.status(runId, RunStatus.COMPLETED);

        assertTrue(repository.guarded(workspace.accountId(), "61000001", UUID.randomUUID()));

        repository.releaseUncertainGuard(workspace.accountId(), "61000001");
        assertTrue(repository.guarded(workspace.accountId(), "61000001", UUID.randomUUID()),
                "подтверждённый отклик нельзя разблокировать как неопределённый");
    }

    @Test
    void releaseUncertainGuardOnlyTouchesUnknownResultsOfFinishedRuns() {
        UUID runId = createRun(List.of(vacancy("62000001", 0)));
        UUID itemId = itemIds(runId).getFirst();
        repository.claim(itemId);
        repository.permit(itemId);
        repository.finish(itemId, Outcome.UNKNOWN, true);

        repository.releaseUncertainGuard(workspace.accountId(), "62000001");
        assertTrue(fixtures.itemGuarded(itemId), "у незавершённого запуска защита сохраняется");

        repository.status(runId, RunStatus.CANCELLED);
        repository.releaseUncertainGuard(workspace.accountId(), "62000001");
        assertFalse(fixtures.itemGuarded(itemId));
    }

    @Test
    void confirmingAnApplicationResolvesOnlyOpenTechnicalTasks() {
        UUID runId = createRun(List.of(vacancy("63000001", 0), vacancy("63000002", 1)));
        List<UUID> items = itemIds(runId);
        repository.finish(items.getFirst(), Outcome.UNKNOWN, true);
        // Защита от повтора остаётся у одной попытки: частичный уникальный индекс не допускает двух.
        repository.finish(items.get(1), Outcome.SENT_LETTER_UNCONFIRMED, false);
        jdbc.sql("update application_item set hh_vacancy_id = '63000001' where id = :id")
                .param("id", items.get(1)).update();

        repository.resolvePreviousApplicationAttention(workspace.accountId(), "63000001");

        assertEquals("RESOLVED", attentionStatus(items.getFirst()));
        assertEquals("OPEN", attentionStatus(items.get(1)),
                "неподтверждённое письмо остаётся открытой задачей пользователя");
    }

    @Test
    void technicalFailureCounterIncrementsAndResets() {
        UUID runId = createRun(List.of(vacancy("70000001", 0)));

        assertEquals(1, repository.recordTechnicalFailure(runId, true));
        assertEquals(2, repository.recordTechnicalFailure(runId, true));
        assertEquals(0, repository.recordTechnicalFailure(runId, false));
        assertEquals(1, repository.recordTechnicalFailure(runId, true));
    }

    @Test
    void busyAndInspectionFlagsFollowTheQueueState() {
        assertFalse(repository.busy());
        assertFalse(repository.inspectionAllowed(workspace.profileId()));

        UUID runId = createRun(List.of(vacancy("80000001", 0)));
        assertTrue(repository.busy());
        assertFalse(repository.inspectionAllowed(workspace.profileId()),
                "во время работающего запуска ручной осмотр браузера не разрешён");

        repository.status(runId, RunStatus.PAUSED_ERROR);
        assertTrue(repository.busy(), "приостановленный запуск всё ещё занимает установку");
        assertTrue(repository.inspectionAllowed(workspace.profileId()));

        repository.status(runId, RunStatus.CANCELLED);
        assertFalse(repository.busy());
        assertFalse(repository.inspectionAllowed(workspace.profileId()));
    }

    @Test
    void existsDistinguishesStoredRunFromAnUnknownRequestId() {
        UUID runId = createRun(List.of(vacancy("90000001", 0)));

        assertTrue(repository.exists(runId));
        assertFalse(repository.exists(UUID.randomUUID()));
    }

    @Test
    void listReturnsOnlyRequestedResumeRunsInDescendingSequenceOrder() {
        UUID older = createRun(List.of());
        repository.status(older, RunStatus.COMPLETED);
        UUID newer = createRun(List.of());
        repository.status(newer, RunStatus.COMPLETED);
        UUID otherResumeId = fixtures.resume(workspace.accountId(), "0123456789abcdef0123", 20);
        UUID foreign = UUID.randomUUID();
        repository.create(new Start(foreign, otherResumeId, List.of(), 20, 10, 10, "", true),
                workspace.accountId(), workspace.profileId(), "0123456789abcdef0123", "Другое резюме", 20);

        var listed = repository.list(workspace.resumeId());

        assertEquals(List.of(newer, older), listed.stream().map(run -> run.id()).toList());
        assertTrue(listed.getFirst().sequence() > listed.getLast().sequence());
        assertTrue(listed.stream().noneMatch(run -> run.id().equals(foreign)));
    }

    private String attentionStatus(UUID itemId) {
        return jdbc.sql("select attention_status from application_item where id = :id")
                .param("id", itemId).query(String.class).single();
    }

    private long activeSeconds(UUID runId) {
        return jdbc.sql("select active_seconds from application_run where id = :id")
                .param("id", runId).query(Long.class).single();
    }

    private java.time.OffsetDateTime activeSince(UUID runId) {
        return jdbc.sql("select active_since from application_run where id = :id")
                .param("id", runId).query(java.time.OffsetDateTime.class).optional().orElse(null);
    }

    private UUID vacancy(String hhVacancyId, int position) {
        return fixtures.queuedVacancy(workspace, hhVacancyId, position);
    }

    private List<UUID> itemIds(UUID runId) {
        return jdbc.sql("select id from application_item where run_id = :id order by position")
                .param("id", runId).query(UUID.class).list();
    }

    private UUID createRun(List<UUID> vacancyIds) {
        UUID runId = UUID.randomUUID();
        var start = new Start(runId, workspace.resumeId(), vacancyIds, 20, 10, 10, "Обычное письмо", true);
        repository.create(start, workspace.accountId(), workspace.profileId(), workspace.hhResumeId(),
                "Java-разработчик", 20);
        int position = 0;
        for (UUID vacancyId : vacancyIds) {
            String hhVacancyId = jdbc.sql("select hh_vacancy_id from vacancy where id = :id")
                    .param("id", vacancyId).query(String.class).single();
            repository.add(runId, workspace.accountId(), vacancyId, hhVacancyId, "Java-разработчик",
                    "Синтетическая компания", SOURCE_URL, 0, position++, false);
        }
        return runId;
    }
}
