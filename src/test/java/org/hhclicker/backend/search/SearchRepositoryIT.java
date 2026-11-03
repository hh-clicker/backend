package org.hhclicker.backend.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;
import org.hhclicker.backend.search.repository.SearchRepository;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchRepository на настоящем PostgreSQL. Схему готовит production-changelog Liquibase,
 * поэтому проверяются реальные ограничения: уникальность пары (resume_id, source_hash),
 * check по лимиту страниц и счётчикам, каскад связей вакансий при удалении поиска.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SearchRepositoryIT extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-04T09:00:00.654321Z");
    private static final Instant LAST_SCANNED_AT = Instant.parse("2026-09-02T12:00:00Z");
    private static final String URL = "https://hh.ru/search/vacancy?items_on_page=100&order_by=publication_time&page=0&text=java";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Autowired
    private SearchRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // Общая для всех интеграционных тестов обвязка: нужны clean/account/resume/vacancy/link.
    private ApplicationDatabaseFixtures fixtures;
    private UUID accountId;
    private UUID resumeId;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        accountId = fixtures.account("Основной аккаунт");
        resumeId = fixtures.resume(accountId, "abcdef0123456789abcd", 20);
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private VacancySearch search(UUID id, UUID owner, String hash, SearchStatus status, Instant createdAt) {
        return new VacancySearch(id, owner, "Java в Москве", URL, hash, 5, status,
                7, 42, LAST_SCANNED_AT, status == SearchStatus.ERROR ? "Прошлая ошибка" : null, createdAt, UPDATED_AT);
    }

    private UUID insert(String hash, SearchStatus status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        repository.insert(search(id, resumeId, hash, status, createdAt));
        return id;
    }

    @Test
    void insertStoresEveryFieldAndReadsItBackWithMicrosecondPrecision() {
        UUID id = UUID.randomUUID();
        repository.insert(search(id, resumeId, HASH, SearchStatus.ERROR, CREATED_AT));

        VacancySearch stored = repository.findById(id).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals(resumeId, stored.resumeId());
        assertEquals("Java в Москве", stored.name());
        assertEquals(URL, stored.sourceUrl());
        assertEquals(HASH, stored.sourceHash());
        assertEquals(5, stored.pagesLimit());
        assertEquals(SearchStatus.ERROR, stored.status());
        assertEquals(7, stored.newVacancies());
        assertEquals(42, stored.totalVacancies());
        assertEquals(LAST_SCANNED_AT, stored.lastScannedAt());
        assertEquals("Прошлая ошибка", stored.lastError());
        assertEquals(CREATED_AT, stored.createdAt());
        assertEquals(UPDATED_AT, stored.updatedAt());
    }

    @Test
    void searchThatWasNeverScannedKeepsNullScanFields() {
        UUID id = UUID.randomUUID();
        repository.insert(new VacancySearch(id, resumeId, "Java в Москве", URL, HASH, 1,
                SearchStatus.ACTIVE, 0, 0, null, null, CREATED_AT, CREATED_AT));

        VacancySearch stored = repository.findById(id).orElseThrow();
        assertNull(stored.lastScannedAt());
        assertNull(stored.lastError());
        assertEquals(0, stored.newVacancies());
        assertEquals(0, stored.totalVacancies());
    }

    @Test
    void findAllReturnsNewestFirstAndBreaksTiesById() {
        Instant older = Instant.parse("2026-08-01T08:00:00Z");
        UUID newest = UUID.randomUUID();
        UUID sameTimeFirst = UUID.randomUUID();
        UUID sameTimeSecond = UUID.randomUUID();
        List<UUID> sameTimeSorted = Stream.of(sameTimeFirst, sameTimeSecond)
                .sorted(Comparator.comparing(UUID::toString)).toList();

        repository.insert(search(newest, resumeId, HASH, SearchStatus.ACTIVE, CREATED_AT));
        repository.insert(search(sameTimeFirst, resumeId, OTHER_HASH, SearchStatus.ACTIVE, older));
        repository.insert(search(sameTimeSecond, resumeId, "c".repeat(64), SearchStatus.ACTIVE, older));

        List<UUID> actual = repository.findAll().stream().map(VacancySearch::id).toList();

        assertEquals(newest, actual.getFirst(), "самый свежий поиск идёт первым");
        assertEquals(sameTimeSorted, actual.subList(1, 3), "при равном created_at порядок задаётся id");
    }

    @Test
    void findAllByResumeIdFiltersAndKeepsTheSameOrder() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        Instant older = Instant.parse("2026-08-01T08:00:00Z");
        UUID mineNewest = insert(HASH, SearchStatus.ACTIVE, CREATED_AT);
        UUID mineOlder = insert(OTHER_HASH, SearchStatus.PAUSED, older);
        repository.insert(search(UUID.randomUUID(), otherResumeId, HASH, SearchStatus.ACTIVE, CREATED_AT));

        assertEquals(List.of(mineNewest, mineOlder),
                repository.findAllByResumeId(resumeId).stream().map(VacancySearch::id).toList());
        assertEquals(3, repository.findAll().size());
        assertTrue(repository.findAllByResumeId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void lookupsByUnknownKeysReturnEmpty() {
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
        assertTrue(repository.findByResumeIdAndSourceHash(resumeId, HASH).isEmpty());
        assertTrue(repository.findAll().isEmpty());
        assertEquals(0, repository.countByResumeId(resumeId));
    }

    @Test
    void searchIsFoundByTheHashOfItsOwnResumeOnly() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        UUID mine = insert(HASH, SearchStatus.ACTIVE, CREATED_AT);

        assertEquals(mine, repository.findByResumeIdAndSourceHash(resumeId, HASH).orElseThrow().id());
        assertTrue(repository.findByResumeIdAndSourceHash(otherResumeId, HASH).isEmpty(),
                "поиск другого резюме не должен находиться по тому же хэшу");
    }

    @Test
    void theSameLinkMayBeSavedForDifferentResumes() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        insert(HASH, SearchStatus.ACTIVE, CREATED_AT);

        UUID second = UUID.randomUUID();
        repository.insert(search(second, otherResumeId, HASH, SearchStatus.ACTIVE, CREATED_AT));

        assertEquals(2, repository.findAll().size());
        assertEquals(second, repository.findByResumeIdAndSourceHash(otherResumeId, HASH).orElseThrow().id());
    }

    @Test
    void theSameLinkCannotBeSavedTwiceForOneResume() {
        insert(HASH, SearchStatus.ACTIVE, CREATED_AT);

        assertThrows(DuplicateKeyException.class, () -> insert(HASH, SearchStatus.PAUSED, CREATED_AT));
        assertEquals(1, repository.countByResumeId(resumeId));
    }

    @Test
    void countIsScopedToTheRequestedResume() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        insert(HASH, SearchStatus.ACTIVE, CREATED_AT);
        insert(OTHER_HASH, SearchStatus.PAUSED, CREATED_AT);
        repository.insert(search(UUID.randomUUID(), otherResumeId, HASH, SearchStatus.ACTIVE, CREATED_AT));

        assertEquals(2, repository.countByResumeId(resumeId));
        assertEquals(1, repository.countByResumeId(otherResumeId));
    }

    @Test
    void lockingReadReturnsTheRowInsideATransaction() {
        UUID id = insert(HASH, SearchStatus.ACTIVE, CREATED_AT);

        transactions.executeWithoutResult(status -> {
            assertEquals(id, repository.findByIdForUpdate(id).orElseThrow().id());
            assertTrue(repository.findByIdForUpdate(UUID.randomUUID()).isEmpty());
        });
    }

    @Test
    void updateDefinitionRewritesTheDefinitionAndAlwaysClearsTheError() {
        UUID id = insert(HASH, SearchStatus.ERROR, CREATED_AT);
        Instant updatedAt = Instant.parse("2026-09-05T12:00:00Z");

        assertTrue(repository.updateDefinition(new VacancySearch(id, resumeId, "Kotlin в Москве",
                "https://hh.ru/search/vacancy?text=kotlin", OTHER_HASH, 12, SearchStatus.ACTIVE,
                999, 999, null, "игнорируемая ошибка", CREATED_AT, updatedAt)));

        VacancySearch stored = repository.findById(id).orElseThrow();
        assertEquals("Kotlin в Москве", stored.name());
        assertEquals("https://hh.ru/search/vacancy?text=kotlin", stored.sourceUrl());
        assertEquals(OTHER_HASH, stored.sourceHash());
        assertEquals(12, stored.pagesLimit());
        assertEquals(SearchStatus.ACTIVE, stored.status());
        assertNull(stored.lastError(), "SQL всегда обнуляет last_error, значение из модели не используется");
        assertEquals(updatedAt, stored.updatedAt());
        assertEquals(7, stored.newVacancies(), "накопленные счётчики сохраняются");
        assertEquals(42, stored.totalVacancies());
        assertEquals(LAST_SCANNED_AT, stored.lastScannedAt(), "время последнего сканирования сохраняется");
        assertEquals(CREATED_AT, stored.createdAt());
    }

    @Test
    void updateStatusTouchesOnlyStatusErrorAndUpdatedAt() {
        UUID id = insert(HASH, SearchStatus.ACTIVE, CREATED_AT);
        Instant updatedAt = Instant.parse("2026-09-05T12:00:00Z");

        assertTrue(repository.updateStatus(new VacancySearch(id, resumeId, "Игнорируемое имя",
                "https://hh.ru/search/vacancy?text=ignored", OTHER_HASH, 20, SearchStatus.ERROR,
                999, 999, null, "Агент не ответил", CREATED_AT, updatedAt)));

        VacancySearch stored = repository.findById(id).orElseThrow();
        assertEquals(SearchStatus.ERROR, stored.status());
        assertEquals("Агент не ответил", stored.lastError());
        assertEquals(updatedAt, stored.updatedAt());
        assertEquals("Java в Москве", stored.name());
        assertEquals(URL, stored.sourceUrl());
        assertEquals(HASH, stored.sourceHash());
        assertEquals(5, stored.pagesLimit());
        assertEquals(7, stored.newVacancies());
        assertEquals(42, stored.totalVacancies());
        assertEquals(LAST_SCANNED_AT, stored.lastScannedAt());
    }

    @Test
    void scanProgressIsStoredAndClearedWithTheFinishedScan() {
        UUID id = insert(HASH, SearchStatus.ACTIVE, CREATED_AT);
        Instant startedAt = Instant.parse("2026-09-05T11:58:00Z");
        Instant progressAt = Instant.parse("2026-09-05T11:59:00Z");
        VacancySearch scanning = new VacancySearch(id, resumeId, "Java в Москве", URL, HASH, 5,
                SearchStatus.SCANNING, 7, 42, LAST_SCANNED_AT, null,
                startedAt, 0, null, 0, null, CREATED_AT, startedAt);
        assertTrue(repository.updateStatus(scanning));

        VacancySearch progress = new VacancySearch(id, resumeId, "Java в Москве", URL, HASH, 5,
                SearchStatus.SCANNING, 7, 42, LAST_SCANNED_AT, null,
                startedAt, 2, 4, 200, 360, CREATED_AT, progressAt);
        assertTrue(repository.updateScanProgress(progress));

        VacancySearch stored = repository.findById(id).orElseThrow();
        assertEquals(startedAt, stored.scanStartedAt());
        assertEquals(2, stored.scanCurrentPage());
        assertEquals(4, stored.scanTotalPages());
        assertEquals(200, stored.scanCollected());
        assertEquals(360, stored.scanTotalResults());

        Instant completedAt = Instant.parse("2026-09-05T12:00:00Z");
        assertTrue(repository.updateAfterIngestion(new VacancySearch(id, resumeId, "Java в Москве", URL, HASH, 5,
                SearchStatus.ACTIVE, 12, 54, completedAt, null, CREATED_AT, completedAt)));
        stored = repository.findById(id).orElseThrow();
        assertNull(stored.scanStartedAt());
        assertNull(stored.scanCurrentPage());
        assertNull(stored.scanTotalPages());
        assertNull(stored.scanCollected());
        assertNull(stored.scanTotalResults());
    }

    @Test
    void updateAfterScanAlsoMovesTheLastScannedTimestamp() {
        UUID id = insert(HASH, SearchStatus.SCANNING, CREATED_AT);
        Instant scannedAt = Instant.parse("2026-09-05T12:00:00Z");

        assertTrue(repository.updateAfterScan(new VacancySearch(id, resumeId, "Игнорируемое имя", URL, HASH, 5,
                SearchStatus.ACTIVE, 999, 999, scannedAt, null, CREATED_AT, scannedAt)));

        VacancySearch stored = repository.findById(id).orElseThrow();
        assertEquals(SearchStatus.ACTIVE, stored.status());
        assertEquals(scannedAt, stored.lastScannedAt());
        assertNull(stored.lastError());
        assertEquals(scannedAt, stored.updatedAt());
        assertEquals(7, stored.newVacancies(), "updateAfterScan не трогает счётчики");
        assertEquals(42, stored.totalVacancies());
    }

    @Test
    void updateAfterIngestionStoresCountersAndClearsTheError() {
        UUID id = insert(HASH, SearchStatus.SCANNING, CREATED_AT);
        Instant scannedAt = Instant.parse("2026-09-05T12:00:00Z");

        assertTrue(repository.updateAfterIngestion(new VacancySearch(id, resumeId, "Игнорируемое имя", URL, HASH, 5,
                SearchStatus.ACTIVE, 11, 130, scannedAt, "игнорируемая ошибка", CREATED_AT, scannedAt)));

        VacancySearch stored = repository.findById(id).orElseThrow();
        assertEquals(SearchStatus.ACTIVE, stored.status());
        assertEquals(11, stored.newVacancies());
        assertEquals(130, stored.totalVacancies());
        assertEquals(scannedAt, stored.lastScannedAt());
        assertEquals(scannedAt, stored.updatedAt());
        assertNull(stored.lastError());
        assertEquals("Java в Москве", stored.name(), "приём результатов не меняет определение поиска");
    }

    @Test
    void everyUpdateReportsNoChangeForAnUnknownSearch() {
        VacancySearch unknown = search(UUID.randomUUID(), resumeId, HASH, SearchStatus.ACTIVE, CREATED_AT);

        assertFalse(repository.updateDefinition(unknown));
        assertFalse(repository.updateStatus(unknown));
        assertFalse(repository.updateAfterScan(unknown));
        assertFalse(repository.updateAfterIngestion(unknown));
        assertFalse(repository.updateScanProgress(unknown));
    }

    @Test
    void deleteRemovesTheSearchAndSecondAttemptReportsNoChange() {
        UUID id = insert(HASH, SearchStatus.ACTIVE, CREATED_AT);

        assertTrue(repository.deleteById(id));
        assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.deleteById(id));
        assertFalse(repository.deleteById(UUID.randomUUID()));
    }

    @Test
    void deletingASearchAlsoRemovesItsVacancyLinksButKeepsTheVacancies() {
        UUID id = insert(HASH, SearchStatus.ACTIVE, CREATED_AT);
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);
        fixtures.link(vacancyId, id, 0);

        assertTrue(repository.deleteById(id));

        assertEquals(0, jdbc.sql("select count(*) from vacancy_search_link").query(Long.class).single(),
                "связи вакансий с поиском удаляются каскадом");
        assertEquals(1, jdbc.sql("select count(*) from vacancy").query(Long.class).single(),
                "сами вакансии переживают удаление поиска");
    }
}
