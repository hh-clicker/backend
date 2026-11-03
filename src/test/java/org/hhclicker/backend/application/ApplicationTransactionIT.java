package org.hhclicker.backend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.hhclicker.backend.application.model.ApplicationModels.Outcome;
import org.hhclicker.backend.application.model.ApplicationModels.Start;
import org.hhclicker.backend.application.repository.ApplicationRepository;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.shared.exception.ApplicationException;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Транзакционные гарантии прикладного сервиса на настоящей БД: сервис вызывается через
 * Spring-прокси, поэтому проверяются реальные {@code @Transactional} и {@code REQUIRES_NEW},
 * а не поведение объекта, созданного через {@code new}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationTransactionIT extends PostgresIntegrationTest {

    @Autowired
    private ApplicationService applications;
    @Autowired
    private ApplicationRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApplicationDatabaseFixtures fixtures;
    private ApplicationDatabaseFixtures.Workspace workspace;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        workspace = fixtures.workspace();
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void applicationServiceIsUsedThroughItsTransactionalProxy() {
        assertTrue(AopUtils.isAopProxy(applications),
                "транзакционные сценарии должны проверяться через Spring-прокси сервиса");
    }

    @Test
    void aiLimitPausesWithoutSubmissionAndExplicitRetryKeepsTheSameVacancy() {
        UUID vacancy = fixtures.queuedVacancy(workspace, "70000001", 0);
        UUID runId = applications.start(start(UUID.randomUUID(), List.of(vacancy))).id();
        transactions.executeWithoutResult(s -> repository.snapshotLetterSettings(runId,
                new org.hhclicker.backend.resume.model.AiLetterSettings(true, "Hello #AI_CHANGE#", 1500), "Resume"));
        var task = applications.claim(workspace.profileId()).orElseThrow();
        var limit = new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter("", "RATE_LIMIT", "RATE_LIMIT", UUID.randomUUID(), 1789829961L);
        assertFalse(applications.prepareLetter(task.itemId(), limit));
        var paused = applications.find(runId);
        assertEquals(org.hhclicker.backend.application.model.ApplicationModels.RunStatus.PAUSED_AI_LIMIT, paused.status());
        assertEquals(java.time.Instant.ofEpochSecond(1789829961L), paused.aiLimitResetsAt());
        assertEquals("QUEUED", repository.item(task.itemId()).status());
        assertNull(repository.item(task.itemId()).submissionStartedAt());
        assertNull(repository.item(task.itemId()).letter());
        assertFalse(applications.permit(task.itemId()));
        applications.complete(task.itemId(), Outcome.STOPPED); // Late result from the retired command.
        assertEquals("QUEUED", repository.item(task.itemId()).status());
        assertTrue(applications.claim(workspace.profileId()).isEmpty());
        applications.changeStatus(runId, org.hhclicker.backend.application.model.ApplicationModels.RunStatus.RUNNING);
        var retry = applications.claim(workspace.profileId()).orElseThrow();
        assertEquals(task.itemId(), retry.itemId());
        assertEquals(1, retry.generationAttempt());
        assertFalse(retry.aiFallbackConfirmed());
        assertNull(applications.find(runId).aiLimitResetsAt());
    }

    @Test
    void fallbackRequiresExplicitRunScopedConsentAndAppliesToRemainingTasks() {
        UUID first = fixtures.queuedVacancy(workspace, "70000002", 0);
        UUID second = fixtures.queuedVacancy(workspace, "70000003", 1);
        UUID runId = applications.start(start(UUID.randomUUID(), List.of(first, second))).id();
        transactions.executeWithoutResult(s -> repository.snapshotLetterSettings(runId,
                new org.hhclicker.backend.resume.model.AiLetterSettings(true, "Hello #AI_CHANGE#", 1500), "Resume"));
        var task = applications.claim(workspace.profileId()).orElseThrow();
        String text = applications.find(runId).coverLetter();
        assertThrows(ApplicationException.class, () -> applications.prepareLetter(task.itemId(),
                new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter(text, "FALLBACK", "RATE_LIMIT_CONFIRMED", null)));
        assertFalse(applications.prepareLetter(task.itemId(),
                new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter(text, "FALLBACK", "RATE_LIMIT", UUID.randomUUID())));
        applications.changeStatus(runId, org.hhclicker.backend.application.model.ApplicationModels.RunStatus.RUNNING, true);
        var confirmed = applications.claim(workspace.profileId()).orElseThrow();
        assertTrue(confirmed.aiFallbackConfirmed());
        assertTrue(applications.find(runId).aiFallbackConfirmed());
        assertTrue(applications.prepareLetter(confirmed.itemId(),
                new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter(text, "FALLBACK", "RATE_LIMIT_CONFIRMED", null)));
        assertTrue(applications.permit(confirmed.itemId()));
        applications.complete(confirmed.itemId(), Outcome.SENT);
        transactions.executeWithoutResult(s -> repository.advance(runId, 0));
        assertTrue(applications.claim(workspace.profileId()).orElseThrow().aiFallbackConfirmed());
    }

    @Test
    void cancelledLimitPauseCannotResumeAndEmptyFallbackCannotBeConfirmed() {
        UUID vacancy = fixtures.queuedVacancy(workspace, "70000004", 0);
        UUID runId = applications.start(new Start(UUID.randomUUID(), workspace.resumeId(), List.of(vacancy), 20, 10, 10, "", true)).id();
        transactions.executeWithoutResult(s -> repository.snapshotLetterSettings(runId,
                new org.hhclicker.backend.resume.model.AiLetterSettings(true, "Hello #AI_CHANGE#", 1500), "Resume"));
        var task = applications.claim(workspace.profileId()).orElseThrow();
        applications.prepareLetter(task.itemId(), new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter("", "RATE_LIMIT", "RATE_LIMIT", UUID.randomUUID()));
        assertThrows(ApplicationException.class, () -> applications.changeStatus(runId,
                org.hhclicker.backend.application.model.ApplicationModels.RunStatus.RUNNING, true));
        assertEquals(org.hhclicker.backend.application.model.ApplicationModels.RunStatus.PAUSED_AI_LIMIT, applications.find(runId).status());
        applications.changeStatus(runId, org.hhclicker.backend.application.model.ApplicationModels.RunStatus.CANCELLED);
        assertEquals("CANCELLED", repository.item(task.itemId()).status());
        assertThrows(ApplicationException.class, () -> applications.changeStatus(runId,
                org.hhclicker.backend.application.model.ApplicationModels.RunStatus.RUNNING));
        assertFalse(applications.permit(task.itemId()));
    }

    private UUID emptyFallbackAiRun(int count) {
        var ids = new java.util.ArrayList<UUID>();
        for (int i = 0; i < count; i++) ids.add(fixtures.queuedVacancy(workspace, "8000000" + i, i));
        UUID runId = applications.start(new Start(UUID.randomUUID(), workspace.resumeId(), ids, 20, 10, 10, "  ", true)).id();
        transactions.executeWithoutResult(s -> repository.snapshotLetterSettings(runId,
                new org.hhclicker.backend.resume.model.AiLetterSettings(true, "Hello #AI_CHANGE#", 1500), "Resume"));
        return runId;
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERVICE_UNAVAILABLE", "NETWORK_ERROR", "TIMEOUT", "VACANCY_TEXT_MISSING", "INVALID_OUTPUT", "EMPTY_LETTER"})
    void temporaryOrLocalAiFailureRequiresAttentionButDoesNotBlockNextVacancy(String code) {
        UUID runId = emptyFallbackAiRun(2);
        var task = applications.claim(workspace.profileId()).orElseThrow();
        var failure = new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter("", "AI_FAILED", code, null);
        assertFalse(applications.prepareLetter(task.itemId(), failure));
        var item = repository.item(task.itemId());
        assertEquals("ATTENTION_REQUIRED", item.status());
        assertEquals(Outcome.AI_LETTER_FAILED, item.outcome());
        assertEquals(failure, item.letter());
        assertNull(item.submissionStartedAt());
        assertTrue(item.message().contains("отклик не отправлялся"));
        assertFalse(applications.permit(task.itemId()));
        applications.complete(task.itemId(), Outcome.STOPPED);
        assertEquals(Outcome.AI_LETTER_FAILED, repository.item(task.itemId()).outcome());
        assertEquals(org.hhclicker.backend.application.model.ApplicationModels.RunStatus.RUNNING, applications.find(runId).status());
        transactions.executeWithoutResult(s -> repository.advance(runId, 0));
        assertNotEquals(task.itemId(), applications.claim(workspace.profileId()).orElseThrow().itemId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTH_REQUIRED", "AUTH_CHECK_FAILED", "MODEL_UNAVAILABLE", "INVALID_TEMPLATE", "RESUME_TEXT_MISSING", "CLI_UNAVAILABLE", "CLI_CONFIG_ERROR", "SCHEMA_REJECTED", "CODEX_FAILED", "GENERATION_FAILED", "INVALID_RESPONSE"})
    void persistentOrUnknownAiFailureImmediatelyPausesQueue(String code) {
        UUID runId = emptyFallbackAiRun(2);
        var task = applications.claim(workspace.profileId()).orElseThrow();
        assertFalse(applications.prepareLetter(task.itemId(),
                new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter("", "AI_FAILED", code, null)));
        assertEquals(org.hhclicker.backend.application.model.ApplicationModels.RunStatus.PAUSED_ERROR, applications.find(runId).status());
        assertEquals(Outcome.AI_LETTER_FAILED, repository.item(task.itemId()).outcome());
        assertNull(repository.item(task.itemId()).submissionStartedAt());
        assertTrue(applications.claim(workspace.profileId()).isEmpty());
    }

    @Test
    void threeConsecutiveTemporaryAiFailuresPauseRemainingQueue() {
        UUID runId = emptyFallbackAiRun(4);
        for (int i = 0; i < 3; i++) {
            var task = applications.claim(workspace.profileId()).orElseThrow();
            applications.prepareLetter(task.itemId(), new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter("", "AI_FAILED", "NETWORK_ERROR", null));
            if (i < 2) {
                assertEquals(org.hhclicker.backend.application.model.ApplicationModels.RunStatus.RUNNING, applications.find(runId).status());
                transactions.executeWithoutResult(s -> repository.advance(runId, 0));
            }
        }
        assertEquals(org.hhclicker.backend.application.model.ApplicationModels.RunStatus.PAUSED_ERROR, applications.find(runId).status());
        assertTrue(applications.claim(workspace.profileId()).isEmpty());
        assertEquals(1, applications.find(runId).applications().stream().filter(item -> item.status().equals("QUEUED")).count());
    }

    @Test
    void successfulApplicationResetsTheTemporaryAiFailureStreak() {
        UUID runId = emptyFallbackAiRun(5);
        for (int i = 0; i < 4; i++) {
            var task = applications.claim(workspace.profileId()).orElseThrow();
            if (i == 1) {
                assertTrue(applications.prepareLetter(task.itemId(), new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter("Hello Generated text", "GENERATED", null, UUID.randomUUID())));
                assertTrue(applications.permit(task.itemId()));
                applications.complete(task.itemId(), Outcome.SENT);
            } else {
                applications.prepareLetter(task.itemId(), new org.hhclicker.backend.application.model.ApplicationModels.PreparedLetter("", "AI_FAILED", "TIMEOUT", null));
            }
            assertEquals(org.hhclicker.backend.application.model.ApplicationModels.RunStatus.RUNNING, applications.find(runId).status());
            transactions.executeWithoutResult(s -> repository.advance(runId, 0));
        }
        assertTrue(applications.claim(workspace.profileId()).isPresent());
    }

    @Test
    void failureInTheMiddleOfStartRollsBackTheRunTogetherWithItsQueue() {
        UUID eligible = fixtures.queuedVacancy(workspace, "10000001", 0);
        UUID removedFromQueue = fixtures.vacancy(workspace.resumeId(), "10000002", "NEW", false);
        fixtures.link(removedFromQueue, workspace.searchId(), 1);

        assertThrows(ApplicationException.class, () -> applications.start(
                start(UUID.randomUUID(), List.of(eligible, removedFromQueue))));

        assertEquals(0, fixtures.count("application_run"), "частично созданный запуск не должен сохраняться");
        assertEquals(0, fixtures.count("application_item"), "очередь частично созданного запуска не должна сохраняться");
    }

    @Test
    void successfulStartIsCommittedAndRepeatedRequestDoesNotCreateASecondRun() {
        UUID first = fixtures.queuedVacancy(workspace, "20000001", 0);
        UUID second = fixtures.queuedVacancy(workspace, "20000002", 1);
        UUID requestId = UUID.randomUUID();

        var created = applications.start(start(requestId, List.of(first, second)));
        var repeated = applications.start(start(requestId, List.of(first, second)));

        assertEquals(created.id(), repeated.id());
        assertEquals(1, fixtures.count("application_run"));
        assertEquals(2, fixtures.count("application_item"));
        assertEquals(List.of(first, second), repeated.applications().stream().map(item -> item.vacancyId()).toList());
    }

    @Test
    void lostCommandIsCommittedEvenWhenTheCallingTransactionRollsBack() {
        UUID vacancyId = fixtures.queuedVacancy(workspace, "30000001", 0);
        applications.start(start(UUID.randomUUID(), List.of(vacancyId)));
        UUID itemId = applications.claim(workspace.profileId()).orElseThrow().itemId();
        String originalAccountName = accountName();

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            jdbc.sql("update hh_account set name = 'Откатанное имя' where id = :id")
                    .param("id", workspace.accountId()).update();
            applications.lost(itemId);
            throw new IllegalStateException("внешняя транзакция должна откатиться");
        }));

        assertEquals(originalAccountName, accountName(), "внешняя транзакция обязана была откатиться");
        assertEquals("ATTENTION_REQUIRED", fixtures.itemStatus(itemId),
                "REQUIRES_NEW обязан сохранить результат потерянной команды");
        assertEquals("NOT_SENT", fixtures.itemOutcome(itemId));
        assertFalse(fixtures.itemGuarded(itemId), "до разрешения на отправку защита от повтора не ставится");
    }

    @Test
    void submissionPermitIsIssuedOnlyOnceForTheSameItem() {
        UUID vacancyId = fixtures.queuedVacancy(workspace, "40000001", 0);
        applications.start(start(UUID.randomUUID(), List.of(vacancyId)));
        UUID itemId = applications.claim(workspace.profileId()).orElseThrow().itemId();

        assertTrue(applications.permit(itemId));
        assertTrue(fixtures.itemGuarded(itemId));
        assertFalse(applications.permit(itemId), "второе разрешение на ту же вакансию выдавать нельзя");
    }

    @Test
    void acknowledgingTheLastOpenTaskCompletesTheRunWithoutClaimingSuccess() {
        UUID vacancyId = fixtures.queuedVacancy(workspace, "50000001", 0);
        UUID runId = applications.start(start(UUID.randomUUID(), List.of(vacancyId))).id();
        UUID itemId = applications.claim(workspace.profileId()).orElseThrow().itemId();
        applications.complete(itemId, Outcome.QUESTIONNAIRE);

        var attention = applications.attention(itemId, "RESOLVED");

        assertEquals("RESOLVED", attention.status());
        assertEquals(Outcome.QUESTIONNAIRE, attention.outcome());
        assertEquals("COMPLETED", fixtures.runStatus(runId));
        assertEquals("ATTENTION_REQUIRED", fixtures.itemStatus(itemId), "закрытие задачи не переписывает историю");
        assertFalse(fixtures.itemGuarded(itemId), "ручное закрытие задачи не создаёт защиту от повтора");
    }

    @Test
    void skippingAttentionAlsoSkipsAndUnqueuesTheVacancy() {
        UUID vacancyId = fixtures.queuedVacancy(workspace, "50000002", 0);
        UUID runId = applications.start(start(UUID.randomUUID(), List.of(vacancyId))).id();
        UUID itemId = applications.claim(workspace.profileId()).orElseThrow().itemId();
        applications.complete(itemId, Outcome.QUESTIONNAIRE);

        var attention = applications.attention(itemId, "SKIPPED");

        assertEquals("SKIPPED", attention.status());
        assertEquals("USER_SKIPPED", jdbc.sql("select status from vacancy where id = :id")
                .param("id", vacancyId).query(String.class).single());
        assertFalse(jdbc.sql("select queued_for_application from vacancy where id = :id")
                .param("id", vacancyId).query(Boolean.class).single());
        assertEquals("COMPLETED", fixtures.runStatus(runId));
    }

    @Test
    void queueOfAFinishedRunCannotBeReopenedByAnotherResult() {
        UUID vacancyId = fixtures.queuedVacancy(workspace, "60000001", 0);
        UUID runId = applications.start(start(UUID.randomUUID(), List.of(vacancyId))).id();
        UUID itemId = applications.claim(workspace.profileId()).orElseThrow().itemId();
        applications.permit(itemId);
        applications.complete(itemId, Outcome.SENT);

        applications.complete(itemId, Outcome.NOT_SENT);

        assertEquals("SUCCEEDED", fixtures.itemStatus(itemId));
        assertEquals("SENT", fixtures.itemOutcome(itemId));
        assertEquals("COMPLETED", fixtures.runStatus(runId));
        assertTrue(repository.guarded(workspace.accountId(), "60000001", UUID.randomUUID()));
    }

    private String accountName() {
        return jdbc.sql("select name from hh_account where id = :id")
                .param("id", workspace.accountId()).query(String.class).single();
    }

    private Start start(UUID requestId, List<UUID> vacancyIds) {
        return new Start(requestId, workspace.resumeId(), vacancyIds, 20, 10, 10, "Обычное письмо", true);
    }
}
