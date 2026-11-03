package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.catalog.service.IngestSearchResultsCommand;
import org.hhclicker.backend.vacancy.catalog.service.VacancyIngestionService;
import org.hhclicker.backend.vacancy.stoplist.service.CompanyBanService;
import org.hhclicker.backend.vacancy.stoplist.service.CreateCompanyBanCommand;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Real PostgreSQL transactions; barriers reproduce the read-ban/insert-vacancy window. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmployerStateConcurrencyIT extends PostgresIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private VacancyIngestionService ingestion;
    @MockitoSpyBean private CompanyBanService bans;
    private ApplicationDatabaseFixtures.Workspace workspace;
    private TransactionTemplate transaction;

    @BeforeEach
    void fixtures() {
        var fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        workspace = fixtures.workspace();
        transaction = new TransactionTemplate(transactions);
    }

    @ParameterizedTest
    @EnumSource(CompanyBanScope.class)
    void blockingAnEmployerCannotCommitBetweenLookupAndVacancyInsertion(CompanyBanScope scope) throws Exception {
        CountDownLatch lookupDone = new CountDownLatch(1);
        CountDownLatch releaseIngestion = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            lookupDone.countDown();
            assertTrue(releaseIngestion.await(10, TimeUnit.SECONDS));
            return result;
        }).when(bans).findApplicableScopes(any(), any());
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> ingest = executor.submit(() -> {
                ingest();
            });
            try {
                assertTrue(lookupDone.await(3, TimeUnit.SECONDS));
                Future<?> ban = executor.submit(() -> transaction.executeWithoutResult(status -> {
                    pid.complete(backendPid());
                    bans.create(command(scope));
                }));
                try {
                    awaitDatabaseLock(pid.get(3, TimeUnit.SECONDS), ban);
                } finally { releaseIngestion.countDown(); }
                ingest.get(5, TimeUnit.SECONDS);
                ban.get(5, TimeUnit.SECONDS);
            } finally { releaseIngestion.countDown(); }
        }
        assertBanned(scope);
    }

    @ParameterizedTest
    @EnumSource(CompanyBanScope.class)
    void ingestionWaitsForAnUncommittedBanBeforeReadingApplicableScopes(CompanyBanScope scope) throws Exception {
        mutateBeforeIngestion(() -> bans.create(command(scope)));
        assertBanned(scope);
    }

    @Test
    void ingestionWaitsForBanDeletionAndDoesNotReapplyADeletedBan() throws Exception {
        UUID id = bans.create(command(CompanyBanScope.GLOBAL)).ban().id();
        mutateBeforeIngestion(() -> bans.delete(id));
        assertEquals("NEW", jdbc.sql("select status from vacancy where resume_id = :id")
                .param("id", workspace.resumeId()).query(String.class).single());
    }

    @Test
    void scopeChangeAndIngestionUseTheSameEmployerLock() throws Exception {
        var fixtures = new ApplicationDatabaseFixtures(jdbc);
        UUID otherResume = fixtures.resume(workspace.accountId(), "0123456789abcdef0123", 20);
        UUID id = bans.create(command(CompanyBanScope.GLOBAL)).ban().id();
        mutateBeforeIngestion(() -> bans.changeScope(id, CompanyBanScope.RESUME, otherResume));
        assertEquals("NEW", jdbc.sql("select status from vacancy where resume_id = :id")
                .param("id", workspace.resumeId()).query(String.class).single());
    }

    private void mutateBeforeIngestion(Runnable mutation) throws Exception {
        mutateBeforeIngestion(mutation, this::ingest);
    }

    @Test
    void employerMoveCannotRetainAConcurrentBanOfThePreviousEmployer() throws Exception {
        ingest();
        mutateBeforeIngestion(() -> bans.create(command(CompanyBanScope.GLOBAL)), () -> ingest("9999"));
        assertEquals("9999", jdbc.sql("select employer_id from vacancy where resume_id = :id")
                .param("id", workspace.resumeId()).query(String.class).single());
        assertEquals("NEW", jdbc.sql("select status from vacancy where resume_id = :id")
                .param("id", workspace.resumeId()).query(String.class).single());
    }

    private void mutateBeforeIngestion(Runnable mutation, Runnable scan) throws Exception {
        CountDownLatch mutated = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                mutation.run();
                mutated.countDown();
                try { assertTrue(releaseMutation.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
            }));
            try {
                assertTrue(mutated.await(3, TimeUnit.SECONDS));
                Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                    pid.complete(backendPid());
                    scan.run();
                }));
                try { awaitDatabaseLock(pid.get(3, TimeUnit.SECONDS), second); }
                finally { releaseMutation.countDown(); }
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally { releaseMutation.countDown(); }
        }
    }

    private void awaitDatabaseLock(int pid, Future<?> waiter) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && !waiter.isDone()) {
            boolean blocked = jdbc.sql("select cardinality(pg_blocking_pids(:pid)) > 0")
                    .param("pid", pid).query(Boolean.class).single();
            if (blocked) return;
            Thread.yield();
        }
        fail("Concurrent operation did not wait for the employer's transaction lock");
    }

    private int backendPid() {
        return jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
    }

    private void ingest() {
        ingest("003529");
    }

    private void ingest(String employer) {
        ingestion.ingest(new IngestSearchResultsCommand(workspace.searchId(), workspace.resumeId(),
                List.of(VacancyFixtures.incoming().employerId(employer).build())));
    }

    private CreateCompanyBanCommand command(CompanyBanScope scope) {
        return new CreateCompanyBanCommand("https://hh.ru/employer/3529", "Synthetic company", scope,
                scope == CompanyBanScope.RESUME ? workspace.resumeId() : null, null);
    }

    private void assertBanned(CompanyBanScope scope) {
        assertEquals("COMPANY_BANNED", jdbc.sql("select status from vacancy where resume_id = :id")
                .param("id", workspace.resumeId()).query(String.class).single());
        assertEquals(scope.name(), jdbc.sql("select ban_scope from vacancy where resume_id = :id")
                .param("id", workspace.resumeId()).query(String.class).single());
        assertFalse(jdbc.sql("select queued_for_application from vacancy where resume_id = :id")
                .param("id", workspace.resumeId()).query(Boolean.class).single());
    }
}
