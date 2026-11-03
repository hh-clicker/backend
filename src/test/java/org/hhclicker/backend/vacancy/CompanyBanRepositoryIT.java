package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;
import org.hhclicker.backend.vacancy.stoplist.repository.CompanyBanRepository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Стоп-лист на настоящем PostgreSQL: частичные уникальные индексы областей, CHECK-инвариант
 * пары scope/resume_id и выборка применимых блокировок.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CompanyBanRepositoryIT extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-04T09:00:00.654321Z");

    @Autowired
    private CompanyBanRepository repository;
    @Autowired
    private JdbcClient jdbc;

    private ApplicationDatabaseFixtures fixtures;
    private UUID accountId;
    private UUID resumeId;
    private UUID otherResumeId;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        accountId = fixtures.account("Основной аккаунт");
        resumeId = fixtures.resume(accountId, "abcdef0123456789abcd", 20);
        otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
    }

    private CompanyBan ban(UUID id, String employerId, CompanyBanScope scope, UUID owner,
                           UUID sourceVacancyId, Instant createdAt) {
        return new CompanyBan(id, employerId, "https://hh.ru/employer/" + employerId, "Синтетическая компания",
                scope, owner, sourceVacancyId, createdAt, UPDATED_AT);
    }

    private UUID insert(String employerId, CompanyBanScope scope, UUID owner) {
        return insert(employerId, scope, owner, null, CREATED_AT);
    }

    private UUID insert(String employerId, CompanyBanScope scope, UUID owner, UUID sourceVacancyId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        repository.insert(ban(id, employerId, scope, owner, sourceVacancyId, createdAt));
        return id;
    }

    @Test
    void insertStoresEveryFieldAndReadsItBack() {
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);
        UUID id = insert("3529", CompanyBanScope.RESUME, resumeId, vacancyId, CREATED_AT);

        CompanyBan stored = repository.findById(id).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals("3529", stored.employerId());
        assertEquals("https://hh.ru/employer/3529", stored.employerUrl());
        assertEquals("Синтетическая компания", stored.companyName());
        assertEquals(CompanyBanScope.RESUME, stored.scope());
        assertEquals(resumeId, stored.resumeId());
        assertEquals(vacancyId, stored.sourceVacancyId());
        assertEquals(CREATED_AT, stored.createdAt());
        assertEquals(UPDATED_AT, stored.updatedAt());
    }

    @Test
    void globalBanIsStoredWithoutAResumeOrASourceVacancy() {
        UUID id = insert("3529", CompanyBanScope.GLOBAL, null);

        CompanyBan stored = repository.findById(id).orElseThrow();
        assertNull(stored.resumeId());
        assertNull(stored.sourceVacancyId());
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void scopeAndResumeMustAgreeWithEachOther() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("3529", CompanyBanScope.RESUME, null));
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("9999", CompanyBanScope.GLOBAL, resumeId));
    }

    @Test
    void oneEmployerCannotBeBlockedGloballyTwice() {
        insert("3529", CompanyBanScope.GLOBAL, null);

        assertThrows(DuplicateKeyException.class, () -> insert("3529", CompanyBanScope.GLOBAL, null));
        assertEquals(1, repository.findAllByEmployerId("3529").size());
    }

    @Test
    void oneEmployerCannotBeBlockedTwiceForTheSameResume() {
        insert("3529", CompanyBanScope.RESUME, resumeId);

        assertThrows(DuplicateKeyException.class, () -> insert("3529", CompanyBanScope.RESUME, resumeId));
    }

    @Test
    void globalAndResumeBansOfOneEmployerCoexistAsIndependentRecords() {
        insert("3529", CompanyBanScope.GLOBAL, null);
        insert("3529", CompanyBanScope.RESUME, resumeId);
        insert("3529", CompanyBanScope.RESUME, otherResumeId);

        assertEquals(3, repository.findAllByEmployerId("3529").size(),
                "перекрытая глобальной блокировка резюме не удаляется");
    }

    @Test
    void stopListIsOrderedByNewestFirst() {
        Instant older = Instant.parse("2026-08-01T08:00:00Z");
        UUID newest = insert("3529", CompanyBanScope.GLOBAL, null);
        UUID first = insert("9999", CompanyBanScope.GLOBAL, null, null, older);
        UUID second = insert("7777", CompanyBanScope.GLOBAL, null, null, older);
        List<UUID> sameTimeSorted = java.util.stream.Stream.of(first, second)
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();

        List<UUID> actual = repository.findAll().stream().map(CompanyBan::id).toList();

        assertEquals(newest, actual.getFirst());
        assertEquals(sameTimeSorted, actual.subList(1, 3));
    }

    @Test
    void resumeSeesItsOwnBansTogetherWithTheGlobalOnes() {
        UUID global = insert("3529", CompanyBanScope.GLOBAL, null);
        UUID mine = insert("9999", CompanyBanScope.RESUME, resumeId);
        insert("7777", CompanyBanScope.RESUME, otherResumeId);

        assertEquals(Set.of(global, mine), repository.findAllVisibleForResume(resumeId).stream()
                .map(CompanyBan::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, repository.findAll().size());
    }

    @Test
    void employerBansAreListedOldestFirst() {
        Instant older = Instant.parse("2026-08-01T08:00:00Z");
        UUID newer = insert("3529", CompanyBanScope.GLOBAL, null);
        UUID older_ = insert("3529", CompanyBanScope.RESUME, resumeId, null, older);

        assertEquals(List.of(older_, newer),
                repository.findAllByEmployerId("3529").stream().map(CompanyBan::id).toList());
        assertTrue(repository.findAllByEmployerId("0000").isEmpty());
    }

    @Test
    void applicableBansAreSelectedByEmployerAndVisibility() {
        UUID global = insert("3529", CompanyBanScope.GLOBAL, null);
        UUID mine = insert("3529", CompanyBanScope.RESUME, resumeId);
        insert("3529", CompanyBanScope.RESUME, otherResumeId);
        UUID otherEmployer = insert("9999", CompanyBanScope.RESUME, resumeId);
        insert("7777", CompanyBanScope.RESUME, otherResumeId);

        var applicable = repository.findApplicable(Set.of("3529", "9999"), resumeId).stream()
                .map(CompanyBan::id).collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(global, mine, otherEmployer), applicable);
        assertTrue(repository.findApplicable(Set.of(), resumeId).isEmpty());
        assertTrue(repository.findApplicable(Set.of("0000"), resumeId).isEmpty());
    }

    @Test
    void distinctBlockedEmployersAreCountedOncePerCompany() {
        insert("3529", CompanyBanScope.GLOBAL, null);
        insert("3529", CompanyBanScope.RESUME, resumeId);
        insert("9999", CompanyBanScope.RESUME, resumeId);
        insert("7777", CompanyBanScope.RESUME, otherResumeId);

        assertEquals(2, repository.countDistinctVisibleEmployers(resumeId),
                "две записи одного работодателя считаются одной компанией");
        assertEquals(2, repository.countDistinctVisibleEmployers(otherResumeId));
        assertEquals(1, repository.countDistinctVisibleEmployers(UUID.randomUUID()));
    }

    @Test
    void scopeUpdateChangesOnlyScopeResumeAndTimestamp() {
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);
        UUID id = insert("3529", CompanyBanScope.RESUME, resumeId, vacancyId, CREATED_AT);
        Instant changedAt = Instant.parse("2026-09-10T10:00:00Z");

        assertTrue(repository.updateScope(new CompanyBan(id, "0000", "https://hh.ru/employer/0000",
                "Игнорируемое название", CompanyBanScope.GLOBAL, null, null, Instant.EPOCH, changedAt)));

        CompanyBan stored = repository.findById(id).orElseThrow();
        assertEquals(CompanyBanScope.GLOBAL, stored.scope());
        assertNull(stored.resumeId());
        assertEquals(changedAt, stored.updatedAt());
        assertEquals("3529", stored.employerId(), "работодатель блокировки не меняется");
        assertEquals("Синтетическая компания", stored.companyName());
        assertEquals(vacancyId, stored.sourceVacancyId());
        assertEquals(CREATED_AT, stored.createdAt());
    }

    @Test
    void scopeUpdateOfAnUnknownBanReportsNoChange() {
        assertFalse(repository.updateScope(ban(UUID.randomUUID(), "3529", CompanyBanScope.GLOBAL, null,
                null, CREATED_AT)));
    }

    @Test
    void deleteRemovesTheBanAndSecondAttemptReportsNoChange() {
        UUID id = insert("3529", CompanyBanScope.GLOBAL, null);

        assertTrue(repository.deleteById(id));
        assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.deleteById(id));
        assertFalse(repository.deleteById(UUID.randomUUID()));
    }

    @Test
    void deletingTheSourceVacancyOnlyClearsTheReference() {
        UUID vacancyId = fixtures.vacancy(resumeId, "118123456", "NEW", false);
        UUID id = insert("3529", CompanyBanScope.GLOBAL, null, vacancyId, CREATED_AT);

        jdbc.sql("delete from vacancy where id = :id").param("id", vacancyId).update();

        CompanyBan stored = repository.findById(id).orElseThrow();
        assertNull(stored.sourceVacancyId(), "исходная вакансия обнуляется, блокировка остаётся");
        assertEquals(CompanyBanScope.GLOBAL, stored.scope());
    }
}
