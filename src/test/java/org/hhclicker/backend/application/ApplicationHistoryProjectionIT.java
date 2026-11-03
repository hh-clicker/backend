package org.hhclicker.backend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.application.dto.ApplicationRunSummaryResponse;
import org.hhclicker.backend.application.model.ApplicationModels.*;
import org.hhclicker.backend.application.repository.ApplicationRepository;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Краткая история запусков и подробная карточка одного запуска.
 * Проверяется, что revision реагирует на изменение элементов очереди,
 * а тексты писем и резюме не попадают в список.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationHistoryProjectionIT extends PostgresIntegrationTest {

    private static final String SOURCE_URL = "https://hh.ru/search/vacancy?page=0";
    private static final String SECRET_LETTER = "СЕКРЕТНОЕ-ПИСЬМО-ДЛЯ-ТЕСТА";
    private static final String SECRET_RESUME = "СЕКРЕТНЫЙ-ТЕКСТ-РЕЗЮМЕ-ДЛЯ-ТЕСТА";

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
    void shortHistoryCountsQueueAndOrdersNewestRunFirst() {
        UUID first = createRun(List.of(vacancy("10000001", 0)));
        repository.status(first, RunStatus.COMPLETED);
        UUID second = createRun(List.of(vacancy("10000002", 1), vacancy("10000003", 2)));

        List<RunSummary> history = repository.summaries(workspace.resumeId());

        assertEquals(2, history.size());
        assertEquals(second, history.getFirst().id());
        assertEquals(2, history.getFirst().total());
        assertEquals(first, history.get(1).id());
        assertEquals(1, history.get(1).total());
        assertTrue(history.getFirst().sequence() > history.get(1).sequence());
    }

    @Test
    void attentionCounterCountsOnlyOpenTasks() {
        UUID runId = createRun(List.of(vacancy("20000001", 0), vacancy("20000002", 1)));
        List<UUID> items = itemIds(runId);
        repository.finish(items.getFirst(), Outcome.QUESTIONNAIRE, false);
        repository.finish(items.get(1), Outcome.NOT_SENT, false);

        assertEquals(2, repository.summaries(workspace.resumeId()).getFirst().attention());

        repository.acknowledge(items.getFirst(), "RESOLVED");

        assertEquals(1, repository.summaries(workspace.resumeId()).getFirst().attention());
    }

    @Test
    void revisionIsStableUntilSomethingInTheQueueChanges() {
        UUID runId = createRun(List.of(vacancy("30000001", 0)));
        String initial = revision();

        assertEquals(initial, revision());

        repository.claim(itemIds(runId).getFirst());
        assertNotEquals(initial, revision(), "переход item в работу должен менять revision");
    }

    @Test
    void revisionReactsToOutcomeAcknowledgementAndPreparedLetter() {
        UUID runId = createRun(List.of(vacancy("31000001", 0)));
        UUID itemId = itemIds(runId).getFirst();

        String queued = revision();
        repository.finish(itemId, Outcome.QUESTIONNAIRE, false);
        String finished = revision();
        assertNotEquals(queued, finished, "результат отклика должен менять revision");

        repository.acknowledge(itemId, "RESOLVED");
        String acknowledged = revision();
        assertNotEquals(finished, acknowledged, "закрытие задачи внимания должно менять revision");

        repository.prepareLetter(itemId, new PreparedLetter(SECRET_LETTER, "STATIC", null, null));
        assertNotEquals(acknowledged, revision(), "подготовленное письмо должно менять revision");
    }

    @Test
    void revisionAlsoReactsToTheRunStatus() {
        UUID runId = createRun(List.of(vacancy("32000001", 0)));
        String running = revision();

        repository.status(runId, RunStatus.PAUSED_USER);

        assertNotEquals(running, revision());
    }

    @Test
    void shortHistoryCarriesNoLetterOrResumeText() {
        UUID runId = createRun(List.of(vacancy("40000001", 0)));
        repository.snapshotLetterSettings(runId, new AiLetterSettings(true, "Здравствуйте!\n#AI_CHANGE#\nСпасибо!", 1500), SECRET_RESUME);
        repository.prepareLetter(itemIds(runId).getFirst(),
                new PreparedLetter(SECRET_LETTER, "GENERATED", null, UUID.randomUUID()));

        RunSummary summary = repository.summaries(workspace.resumeId()).getFirst();
        ApplicationRunSummaryResponse response = ApplicationRunSummaryResponse.from(summary);

        assertNoPayloadComponents(RunSummary.class);
        assertNoPayloadComponents(ApplicationRunSummaryResponse.class);
        assertFalse(summary.toString().contains(SECRET_LETTER), "текст письма попал в краткую историю");
        assertFalse(summary.toString().contains(SECRET_RESUME), "текст резюме попал в краткую историю");
        assertFalse(response.toString().contains(SECRET_LETTER));
        assertFalse(response.toString().contains(SECRET_RESUME));
    }

    @Test
    void runDetailsExposeQueueLetterAndGenerationSnapshot() {
        UUID runId = createRun(List.of(vacancy("50000001", 0), vacancy("50000002", 1)));
        var ai = new AiLetterSettings(true, "Здравствуйте!\n#AI_CHANGE#\nСпасибо!", 1200);
        repository.snapshotLetterSettings(runId, ai, SECRET_RESUME);
        UUID itemId = itemIds(runId).getFirst();
        UUID generationId = UUID.randomUUID();
        repository.prepareLetter(itemId, new PreparedLetter(SECRET_LETTER, "GENERATED", null, generationId));

        var run = repository.find(runId).orElseThrow();

        assertEquals(2, run.applications().size());
        assertEquals(ai, run.aiLetter());
        assertEquals("Обычное письмо", run.coverLetter());
        assertEquals(new PreparedLetter(SECRET_LETTER, "GENERATED", null, generationId),
                run.applications().getFirst().letter());
        assertNull(run.applications().get(1).letter());
        assertEquals("50000001", run.applications().getFirst().hhVacancyId());
    }

    @Test
    void runWithoutQueueStillHasAStableSummary() {
        UUID runId = UUID.randomUUID();
        repository.create(new Start(runId, workspace.resumeId(), List.of(), 20, 10, 10, "Обычное письмо", true),
                workspace.accountId(), workspace.profileId(), workspace.hhResumeId(), "Java-разработчик", 20);

        RunSummary summary = repository.summaries(workspace.resumeId()).getFirst();

        assertEquals(0, summary.total());
        assertEquals(0, summary.attention());
        assertEquals(summary.revision(), repository.summaries(workspace.resumeId()).getFirst().revision());
    }

    @Test
    void historyEstimatesRemainingTimeFromActiveIntervalsAndProcessedItems() {
        UUID runId = createRun(List.of(vacancy("55000001", 0), vacancy("55000002", 1)));
        UUID first = itemIds(runId).getFirst();
        jdbc.sql("update application_item set status = 'SUCCEEDED' where id = :id").param("id", first).update();
        repository.status(runId, RunStatus.PAUSED_USER);
        jdbc.sql("update application_run set active_seconds = 120 where id = :id").param("id", runId).update();

        RunSummary summary = repository.summaries(workspace.resumeId()).getFirst();

        assertEquals(120L, summary.estimatedRemainingSeconds());
    }

    @Test
    void historyOfAnotherResumeIsNotReturned() {
        createRun(List.of(vacancy("60000001", 0)));
        UUID otherResume = fixtures.resume(workspace.accountId(), "0123456789abcdef0123", 20);

        assertTrue(repository.summaries(otherResume).isEmpty());
    }

    private void assertNoPayloadComponents(Class<?> type) {
        for (RecordComponent component : type.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().matches(".*(letter|text|plain).*"),
                    () -> "краткая история не должна содержать поле " + type.getSimpleName() + "." + component.getName());
        }
    }

    private String revision() {
        return repository.summaries(workspace.resumeId()).getFirst().revision();
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
        repository.create(new Start(runId, workspace.resumeId(), vacancyIds, 20, 10, 10, "Обычное письмо", true),
                workspace.accountId(), workspace.profileId(), workspace.hhResumeId(), "Java-разработчик", 20);
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
