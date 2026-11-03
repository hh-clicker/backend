package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;
import org.hhclicker.backend.vacancy.catalog.model.VacancySearchLink;
import org.hhclicker.backend.vacancy.catalog.repository.VacancySearchLinkRepository;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Связь вакансии с поиском: идемпотентный upsert и счётчики последнего сканирования.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VacancySearchLinkRepositoryIT extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-04T09:00:00Z");

    @Autowired
    private VacancySearchLinkRepository repository;
    @Autowired
    private JdbcClient jdbc;

    private ApplicationDatabaseFixtures fixtures;
    private UUID accountId;
    private UUID resumeId;
    private UUID searchId;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        accountId = fixtures.account("Основной аккаунт");
        resumeId = fixtures.resume(accountId, "abcdef0123456789abcd", 20);
        searchId = fixtures.search(resumeId, "abcdef0123456789abcd");
    }

    private UUID link(UUID vacancyId, UUID search, int position, Instant createdAt, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        repository.upsert(new VacancySearchLink(id, vacancyId, search, position, createdAt, updatedAt));
        return id;
    }

    private long count(String sql, UUID parameter) {
        return jdbc.sql(sql).param("id", parameter).query(Long.class).single();
    }

    @Test
    void newLinkIsStoredWithItsPosition() {
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);

        UUID linkId = link(vacancyId, searchId, 42, CREATED_AT, CREATED_AT);

        assertEquals(1, count("select count(*) from vacancy_search_link where search_id = :id", searchId));
        assertEquals(42, jdbc.sql("select position from vacancy_search_link where id = :id")
                .param("id", linkId).query(Integer.class).single());
    }

    @Test
    void repeatedDiscoveryUpdatesOnlyThePositionAndTheTimestamp() {
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);
        UUID firstLinkId = link(vacancyId, searchId, 10, CREATED_AT, CREATED_AT);

        link(vacancyId, searchId, 3, UPDATED_AT, UPDATED_AT);

        assertEquals(1, count("select count(*) from vacancy_search_link where search_id = :id", searchId));
        var row = jdbc.sql("""
                select id, position, created_at, updated_at from vacancy_search_link
                where vacancy_id = :id
                """).param("id", vacancyId).query((rs, n) -> new Object[]{
                rs.getObject("id", UUID.class), rs.getInt("position"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()}).single();

        assertEquals(firstLinkId, row[0], "идентификатор связи не меняется");
        assertEquals(3, row[1]);
        assertEquals(CREATED_AT, row[2], "время первого обнаружения сохраняется");
        assertEquals(UPDATED_AT, row[3]);
    }

    @Test
    void oneVacancyMayBelongToSeveralSearches() {
        UUID secondSearchId = fixtures.search(resumeId, "second-search-seed");
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);

        link(vacancyId, searchId, 0, CREATED_AT, CREATED_AT);
        link(vacancyId, secondSearchId, 1, CREATED_AT, CREATED_AT);

        assertEquals(1, repository.countVacanciesBySearchId(searchId));
        assertEquals(1, repository.countVacanciesBySearchId(secondSearchId));
        assertEquals(2, count("select count(*) from vacancy_search_link where vacancy_id = :id", vacancyId));
    }

    @Test
    void countersSeparateNewVacanciesFromTheRest() {
        UUID fresh = fixtures.vacancy(resumeId, "118000001", "NEW", false);
        UUID skipped = fixtures.vacancy(resumeId, "118000002", "USER_SKIPPED", false);
        UUID closed = fixtures.vacancy(resumeId, "118000003", "CLOSED", false);
        link(fresh, searchId, 0, CREATED_AT, CREATED_AT);
        link(skipped, searchId, 1, CREATED_AT, CREATED_AT);
        link(closed, searchId, 2, CREATED_AT, CREATED_AT);

        assertEquals(3, repository.countVacanciesBySearchId(searchId));
        assertEquals(1, repository.countNewVacanciesBySearchId(searchId));
    }

    @Test
    void countersOfAnUnknownSearchAreZero() {
        assertEquals(0, repository.countVacanciesBySearchId(UUID.randomUUID()));
        assertEquals(0, repository.countNewVacanciesBySearchId(UUID.randomUUID()));
    }

    @Test
    void removingTheLinkKeepsTheVacancyItself() {
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);
        link(vacancyId, searchId, 0, CREATED_AT, CREATED_AT);

        jdbc.sql("delete from vacancy_search_link where vacancy_id = :id").param("id", vacancyId).update();

        assertEquals(0, repository.countVacanciesBySearchId(searchId));
        assertEquals(1, count("select count(*) from vacancy where id = :id", vacancyId),
                "вакансия принадлежит резюме и переживает потерю связи с поиском");
    }
}
