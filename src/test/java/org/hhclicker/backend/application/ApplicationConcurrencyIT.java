package org.hhclicker.backend.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.hhclicker.backend.application.model.ApplicationModels.Start;
import org.hhclicker.backend.application.repository.ApplicationRepository;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Конкурентные сценарии на настоящих независимых транзакциях.
 * <p>
 * Тестовый класс намеренно не помечен {@code @Transactional}: наблюдаются коммиты фоновых потоков,
 * которые внутри откатываемой тестовой транзакции были бы не видны. Синхронизация — только
 * через {@link CountDownLatch} с ограниченными таймаутами, без {@code Thread.sleep}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationConcurrencyIT extends PostgresIntegrationTest {

    private static final int TIMEOUT_SECONDS = 10;

    @Autowired
    private ApplicationRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApplicationDatabaseFixtures fixtures;
    private ApplicationDatabaseFixtures.Workspace workspace;
    private TransactionTemplate transactions;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        workspace = fixtures.workspace();
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void advisoryLockSerializesConcurrentTransactions() throws InterruptedException {
        var firstHoldsLock = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondAsksForLock = new CountDownLatch(1);
        var secondHoldsLock = new CountDownLatch(1);
        var failures = new CopyOnWriteArrayList<Throwable>();

        executor.execute(() -> guard(failures, () -> transactions.executeWithoutResult(status -> {
            repository.lock();
            firstHoldsLock.countDown();
            await(releaseFirst, "первая транзакция не дождалась сигнала на освобождение");
        })));
        assertTrue(firstHoldsLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "первая транзакция не взяла блокировку");

        executor.execute(() -> guard(failures, () -> transactions.executeWithoutResult(status -> {
            secondAsksForLock.countDown();
            repository.lock();
            secondHoldsLock.countDown();
        })));
        assertTrue(secondAsksForLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "вторая транзакция не стартовала");

        assertFalse(secondHoldsLock.await(500, TimeUnit.MILLISECONDS),
                "вторая транзакция получила блокировку, пока первая её удерживает");

        releaseFirst.countDown();
        assertTrue(secondHoldsLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "вторая транзакция не получила блокировку после коммита первой");
        assertTrue(failures.isEmpty(), () -> "ошибки фоновых транзакций: " + failures);
    }

    @Test
    void onlyOneOfTwoConcurrentRunsSurvivesTheGlobalUniqueIndex() throws InterruptedException {
        UUID firstVacancy = fixtures.queuedVacancy(workspace, "10000001", 0);
        UUID secondVacancy = fixtures.queuedVacancy(workspace, "10000002", 1);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var succeeded = new AtomicInteger();
        var rejected = new AtomicInteger();
        var failures = new CopyOnWriteArrayList<Throwable>();

        for (UUID vacancyId : List.of(firstVacancy, secondVacancy)) {
            executor.execute(() -> {
                try {
                    ready.countDown();
                    await(go, "поток не дождался общего старта");
                    transactions.executeWithoutResult(status -> createRun(vacancyId));
                    succeeded.incrementAndGet();
                } catch (DuplicateKeyException expected) {
                    rejected.incrementAndGet();
                } catch (Throwable unexpected) {
                    failures.add(unexpected);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "потоки не подготовились");
        go.countDown();
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "конкурентные запуски не завершились");

        assertTrue(failures.isEmpty(), () -> "неожиданные ошибки: " + failures);
        assertEquals(1, succeeded.get(), "должен выжить ровно один запуск");
        assertEquals(1, rejected.get(), "второй запуск должен быть отклонён уникальным индексом");
        assertEquals(1, fixtures.count("application_run"));
    }

    @Test
    void committedWorkOfABackgroundThreadIsVisibleToTheTest() throws InterruptedException {
        UUID vacancyId = fixtures.queuedVacancy(workspace, "20000001", 0);
        var committed = new CountDownLatch(1);
        var failures = new CopyOnWriteArrayList<Throwable>();

        executor.execute(() -> guard(failures, () -> {
            transactions.executeWithoutResult(status -> createRun(vacancyId));
            committed.countDown();
        }));

        assertTrue(committed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "фоновая транзакция не закоммитилась");
        assertTrue(failures.isEmpty(), () -> "ошибки фоновых транзакций: " + failures);
        assertEquals(1, fixtures.count("application_run"),
                "тестовая транзакция не должна скрывать коммиты фоновых потоков");
        assertEquals(1, fixtures.count("application_item"));
    }

    private void createRun(UUID vacancyId) {
        UUID runId = UUID.randomUUID();
        repository.create(new Start(runId, workspace.resumeId(), List.of(vacancyId), 20, 10, 10, "Обычное письмо", true),
                workspace.accountId(), workspace.profileId(), workspace.hhResumeId(), "Java-разработчик", 20);
        String hhVacancyId = jdbc.sql("select hh_vacancy_id from vacancy where id = :id")
                .param("id", vacancyId).query(String.class).single();
        repository.add(runId, workspace.accountId(), vacancyId, hhVacancyId, "Java-разработчик",
                "Синтетическая компания", "https://hh.ru/search/vacancy?page=0", 0, 0, false);
    }

    private void guard(List<Throwable> failures, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            failures.add(throwable);
        }
    }

    private void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }
}
