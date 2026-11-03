package org.hhclicker.backend.vacancy;

import tools.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;
import org.hhclicker.backend.vacancy.catalog.model.*;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.catalog.repository.VacancySearchLinkRepository;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VacancyRepository на настоящем PostgreSQL: условные update, массовые операции стоп-листа
 * и CHECK-инварианты каталога, которые нельзя проверить моками.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VacancyRepositoryIT extends PostgresIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-09-01T08:00:00.123456Z");
    private static final Instant LAST_SEEN = Instant.parse("2026-09-04T09:00:00.654321Z");

    @Autowired
    private VacancyRepository repository;
    @Autowired
    private VacancySearchLinkRepository links;
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

    private Vacancy vacancy(UUID id, UUID owner, String hhVacancyId, String employerId,
                            VacancyStatus status, CompanyBanScope banScope, boolean queued, Instant firstSeen) {
        return new Vacancy(id, owner, hhVacancyId, "Java-разработчик", employerId, "Синтетическая компания",
                new VacancySalary(100_000L, 200_000L, SalaryCurrency.RUR, true), "Москва", "Удалённо", "1-3 года",
                FIRST_SEEN, "от 100 000 ₽", List.of(), Boolean.TRUE,
                status, banScope, queued, firstSeen, LAST_SEEN, FIRST_SEEN, LAST_SEEN, null);
    }

    private UUID insert(String hhVacancyId, String employerId, VacancyStatus status,
                        CompanyBanScope banScope, boolean queued) {
        return insert(resumeId, hhVacancyId, employerId, status, banScope, queued, FIRST_SEEN);
    }

    private UUID insert(UUID owner, String hhVacancyId, String employerId, VacancyStatus status,
                        CompanyBanScope banScope, boolean queued, Instant firstSeen) {
        UUID id = UUID.randomUUID();
        UUID inserted = repository.insertIfAbsent(
                vacancy(id, owner, hhVacancyId, employerId, status, banScope, queued, firstSeen)).orElseThrow();
        assertEquals(id, inserted);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.sql("select status from vacancy where id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void insertStoresEveryFieldAndReadsItBack() {
        UUID id = insert("118123456", "3529", VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false);
        repository.replaceLabels(id, List.of("Быстрый ответ", "Проверенный"));

        Vacancy stored = repository.findById(id).orElseThrow();
        assertEquals(resumeId, stored.resumeId());
        assertEquals("118123456", stored.hhVacancyId());
        assertEquals("Java-разработчик", stored.title());
        assertEquals("3529", stored.employerId());
        assertEquals("Синтетическая компания", stored.companyName());
        assertEquals(100_000L, stored.salary().from());
        assertEquals(200_000L, stored.salary().to());
        assertEquals(SalaryCurrency.RUR, stored.salary().currency());
        assertTrue(stored.salary().gross());
        assertEquals("Москва", stored.location());
        assertEquals("Удалённо", stored.workFormat());
        assertEquals("1-3 года", stored.experience());
        assertEquals(FIRST_SEEN, stored.publishedAt());
        assertEquals("от 100 000 ₽", stored.salaryText());
        assertEquals(List.of("Быстрый ответ", "Проверенный"), stored.labels());
        assertEquals(Boolean.TRUE, stored.hasQuestionnaire());
        assertEquals(VacancyStatus.COMPANY_BANNED, stored.status());
        assertEquals(CompanyBanScope.GLOBAL, stored.banScope());
        assertFalse(stored.queuedForApplication());
        assertEquals(FIRST_SEEN, stored.firstSeenAt());
        assertEquals(LAST_SEEN, stored.lastSeenAt());
        assertEquals(0, stored.textLength());
    }

    @Test
    void listingExposesSavedTextLengthWithoutLoadingTheDescription() {
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, false);
        repository.saveText(id, "Java и Kotlin");

        Vacancy stored = repository.findById(id).orElseThrow();

        assertEquals(13, stored.textLength());
        assertEquals("Java и Kotlin", repository.findText(id).orElseThrow().plainText());
    }

    @Test
    void enrichmentIsStoredAsJsonbAndSurvivesExternalUpdates() {
        var first = JsonNodeFactory.instance.objectNode();
        first.putArray("workFormats").add("REMOTE").add("HYBRID");
        first.put("responsesCount", 42);
        Vacancy base = vacancy(UUID.randomUUID(), resumeId, "118123456", "3529",
                VacancyStatus.NEW, null, false, FIRST_SEEN);
        Vacancy enriched = new Vacancy(
                base.id(), base.resumeId(), base.hhVacancyId(), base.title(), base.employerId(), base.companyName(),
                base.salary(), base.location(), base.workFormat(), base.experience(), base.publishedAt(),
                base.salaryText(), base.labels(), base.hasQuestionnaire(), base.status(), base.banScope(),
                base.queuedForApplication(), base.firstSeenAt(), base.lastSeenAt(), base.createdAt(), base.updatedAt(),
                base.relevance(), first
        );
        repository.insertIfAbsent(enriched).orElseThrow();

        assertEquals(first, repository.findById(base.id()).orElseThrow().enrichment());

        var second = JsonNodeFactory.instance.objectNode().put("responsesCount", 57);
        Vacancy refreshed = new Vacancy(
                base.id(), base.resumeId(), base.hhVacancyId(), base.title(), base.employerId(), base.companyName(),
                base.salary(), base.location(), base.workFormat(), base.experience(), base.publishedAt(),
                base.salaryText(), base.labels(), base.hasQuestionnaire(), base.status(), base.banScope(),
                base.queuedForApplication(), base.firstSeenAt(), LAST_SEEN, base.createdAt(), LAST_SEEN,
                base.relevance(), second
        );
        assertTrue(repository.updateExternalData(refreshed));
        assertEquals(second, repository.findById(base.id()).orElseThrow().enrichment());
    }

    @Test
    void relevanceIsInitiallyNullAndCanBeStoredAsOneCompleteValue() {
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, false);
        assertNull(repository.findById(id).orElseThrow().relevance());
        Instant analyzedAt = Instant.parse("2026-09-11T10:00:00Z");

        assertTrue(repository.saveRelevance(id, new VacancyRelevance(
                VacancyRelevanceCategory.TARGET, VacancyRelevanceConfidence.high,
                "Явная PHP-разработка.", analyzedAt)));

        VacancyRelevance stored = repository.findById(id).orElseThrow().relevance();
        assertEquals(VacancyRelevanceCategory.TARGET, stored.category());
        assertEquals(VacancyRelevanceConfidence.high, stored.confidence());
        assertEquals("Явная PHP-разработка.", stored.reason());
        assertEquals(analyzedAt, stored.analyzedAt());
        assertFalse(repository.saveRelevance(UUID.randomUUID(), stored));
    }

    @Test
    void vacancyWithoutOptionalDataIsStoredWithNulls() {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(new Vacancy(id, resumeId, "118123456", "Java-разработчик", null,
                "Работодатель не указан", null, "", "", "", null, "", List.of(), null,
                VacancyStatus.NEW, null, false, FIRST_SEEN, FIRST_SEEN, FIRST_SEEN, FIRST_SEEN, null));

        Vacancy stored = repository.findById(id).orElseThrow();
        assertNull(stored.employerId());
        assertNull(stored.salary());
        assertNull(stored.publishedAt());
        assertNull(stored.hasQuestionnaire());
        assertNull(stored.banScope());
        assertEquals(List.of(), stored.labels());
    }

    @Test
    void insertIsIdempotentWithinOneResume() {
        insert("118123456", "3529", VacancyStatus.NEW, null, false);

        var second = repository.insertIfAbsent(vacancy(UUID.randomUUID(), resumeId, "118123456", "9999",
                VacancyStatus.CLOSED, null, false, FIRST_SEEN));

        assertTrue(second.isEmpty(), "повторная вставка не создаёт вторую строку");
        assertEquals(1, jdbc.sql("select count(*) from vacancy").query(Long.class).single());
        assertEquals("3529", repository.findById(
                        repository.findAllByResumeIdAndHhVacancyIds(resumeId, List.of("118123456")).getFirst().id())
                .orElseThrow().employerId());
    }

    @Test
    void theSameHhVacancyMayBelongToDifferentResumes() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        insert("118123456", "3529", VacancyStatus.NEW, null, false);

        UUID second = insert(otherResumeId, "118123456", "3529", VacancyStatus.NEW, null, false, FIRST_SEEN);

        assertEquals(2, jdbc.sql("select count(*) from vacancy").query(Long.class).single());
        assertEquals(otherResumeId, repository.findById(second).orElseThrow().resumeId());
    }

    @Test
    void listingsAreOrderedByNewestDiscoveryThenById() {
        Instant older = Instant.parse("2026-08-01T08:00:00Z");
        UUID newest = insert("118000001", "3529", VacancyStatus.NEW, null, false);
        UUID olderFirst = insert(resumeId, "118000002", "3529", VacancyStatus.NEW, null, false, older);
        UUID olderSecond = insert(resumeId, "118000003", "3529", VacancyStatus.NEW, null, false, older);
        List<UUID> olderSorted = java.util.stream.Stream.of(olderFirst, olderSecond)
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();

        List<UUID> actual = repository.findAllListings().stream()
                .map(listing -> listing.vacancy().id()).toList();

        assertEquals(newest, actual.getFirst());
        assertEquals(olderSorted, actual.subList(1, 3));
    }

    @Test
    void listingsAreFilteredByResumeAndBySearch() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        UUID mine = insert("118000001", "3529", VacancyStatus.NEW, null, false);
        UUID foreign = insert(otherResumeId, "118000002", "3529", VacancyStatus.NEW, null, false, FIRST_SEEN);
        links.upsert(new VacancySearchLink(UUID.randomUUID(), mine, searchId, 0, FIRST_SEEN, FIRST_SEEN));

        assertEquals(List.of(mine), repository.findListingsByResumeId(resumeId).stream()
                .map(listing -> listing.vacancy().id()).toList());
        assertEquals(List.of(mine), repository.findListingsBySearchId(searchId).stream()
                .map(listing -> listing.vacancy().id()).toList());
        assertEquals(2, repository.findAllListings().size());
        assertTrue(repository.findListingsBySearchId(UUID.randomUUID()).isEmpty());
        assertEquals(foreign, repository.findListingsByResumeId(otherResumeId).getFirst().vacancy().id());
    }

    @Test
    void listingCarriesTheEarliestSearchLink() {
        UUID secondSearchId = fixtures.search(resumeId, "second-search-seed");
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, false);
        links.upsert(new VacancySearchLink(UUID.randomUUID(), id, secondSearchId, 0,
                Instant.parse("2026-09-03T08:00:00Z"), LAST_SEEN));
        links.upsert(new VacancySearchLink(UUID.randomUUID(), id, searchId, 0,
                Instant.parse("2026-09-01T08:00:00Z"), LAST_SEEN));

        assertEquals(searchId, repository.findListingById(id).orElseThrow().searchId(),
                "в проекции показывается первая по времени связь");
    }

    @Test
    void bulkLookupsIgnoreEmptyInputAndUnknownIdentifiers() {
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, false);

        assertTrue(repository.findAllByIds(List.of()).isEmpty());
        assertTrue(repository.findListingsByIds(List.of()).isEmpty());
        assertTrue(repository.findAllByResumeIdAndHhVacancyIds(resumeId, List.of()).isEmpty());
        assertTrue(repository.findAllByIds(List.of(UUID.randomUUID())).isEmpty());
        assertEquals(List.of(id), repository.findAllByIds(List.of(id, UUID.randomUUID())).stream()
                .map(Vacancy::id).toList());
        assertTrue(repository.findAllByResumeIdAndHhVacancyIds(UUID.randomUUID(), List.of("118123456")).isEmpty());
    }

    @Test
    void countsOfTheResumeSeparateNewAndQueuedVacancies() {
        insert("118000001", "3529", VacancyStatus.NEW, null, false);
        insert("118000002", "3529", VacancyStatus.NEW, null, true);
        insert("118000003", "3529", VacancyStatus.USER_SKIPPED, null, true);
        insert("118000004", "3529", VacancyStatus.CLOSED, null, false);

        var counts = repository.getCountsByResumeId(resumeId);

        assertEquals(2, counts.newVacancies());
        assertEquals(2, counts.queuedVacancies());
    }

    @Test
    void latestCompanyNameComesFromTheMostRecentlySeenVacancy() {
        insert("118000001", "3529", VacancyStatus.NEW, null, false);
        UUID newer = UUID.randomUUID();
        repository.insertIfAbsent(new Vacancy(newer, resumeId, "118000002", "Java-разработчик", "3529",
                "ООО Свежее название", null, "", "", "", null, "", List.of(), null, VacancyStatus.NEW, null, false,
                FIRST_SEEN, Instant.parse("2026-09-09T09:00:00Z"), FIRST_SEEN, LAST_SEEN, null));

        assertEquals("ООО Свежее название", repository.findLatestCompanyName("3529").orElseThrow());
        assertTrue(repository.findLatestCompanyName("0000").isEmpty());
    }

    @Test
    void blockedVacanciesAreCountedPerEmployerAndResume() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        insert("118000001", "3529", VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false);
        insert("118000002", "3529", VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false);
        insert(otherResumeId, "118000003", "3529", VacancyStatus.COMPANY_BANNED, CompanyBanScope.GLOBAL, false, FIRST_SEEN);
        insert("118000004", "3529", VacancyStatus.NEW, null, false);

        var counts = repository.countBannedVacanciesByEmployerAndResume(Set.of("3529"));

        assertEquals(2, counts.size(), "строки группируются по паре работодатель + резюме");
        assertEquals(3, counts.stream().mapToLong(count -> count.count()).sum());
        assertTrue(repository.countBannedVacanciesByEmployerAndResume(Set.of()).isEmpty());
    }

    @Test
    void externalUpdateRefreshesHhDataAndKeepsUserStateAndDescription() {
        UUID id = insert("118123456", "3529", VacancyStatus.USER_SKIPPED, null, true);
        repository.saveText(id, "Сохранённое описание");

        Instant refreshedAt = Instant.parse("2026-09-10T10:00:00Z");
        assertTrue(repository.updateExternalData(new Vacancy(id, resumeId, "118123456", "Senior Java",
                "9999", "Новая компания", null, "СПб", "Гибрид", "3-6 лет", null, "по договорённости",
                List.of(), Boolean.FALSE, VacancyStatus.CLOSED, CompanyBanScope.GLOBAL, false,
                FIRST_SEEN, refreshedAt, FIRST_SEEN, refreshedAt, null)));

        Vacancy stored = repository.findById(id).orElseThrow();
        assertEquals("Senior Java", stored.title());
        assertEquals("9999", stored.employerId());
        assertNull(stored.salary());
        assertEquals(refreshedAt, stored.lastSeenAt());
        assertEquals(VacancyStatus.USER_SKIPPED, stored.status(), "внешние данные не переписывают статус");
        assertTrue(stored.queuedForApplication(), "очередь пользователя переживает обновление выдачи");
        assertEquals("Сохранённое описание", repository.findText(id).orElseThrow().plainText(),
                "снимок описания не стирается сканированием");
    }

    @Test
    void conditionalStateUpdateOnlyFiresFromAnExpectedStatus() {
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, false);

        assertFalse(repository.updateState(id, EnumSet.of(VacancyStatus.CLOSED), VacancyStatus.USER_SKIPPED,
                null, false, LAST_SEEN));
        assertEquals("NEW", statusOf(id));

        assertFalse(repository.updateState(id, EnumSet.noneOf(VacancyStatus.class), VacancyStatus.USER_SKIPPED,
                null, false, LAST_SEEN));

        assertTrue(repository.updateState(id, EnumSet.of(VacancyStatus.NEW), VacancyStatus.USER_SKIPPED,
                null, true, LAST_SEEN));
        assertEquals("USER_SKIPPED", statusOf(id));
        assertTrue(repository.findById(id).orElseThrow().queuedForApplication());
    }

    @Test
    void globalBanTouchesEveryResumeButLeavesTerminalStatuses() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        UUID active = insert("118000001", "3529", VacancyStatus.NEW, null, true);
        UUID skipped = insert("118000002", "3529", VacancyStatus.USER_SKIPPED, null, false);
        UUID foreign = insert(otherResumeId, "118000003", "3529", VacancyStatus.NEW, null, false, FIRST_SEEN);
        UUID applied = insert("118000004", "3529", VacancyStatus.ALREADY_APPLIED, null, false);
        UUID otherEmployer = insert("118000005", "9999", VacancyStatus.NEW, null, false);

        List<UUID> changed = repository.banVacanciesOfEmployer("3529", CompanyBanScope.GLOBAL, null, LAST_SEEN);

        assertEquals(Set.of(active, skipped, foreign), Set.copyOf(changed));
        assertEquals("ALREADY_APPLIED", statusOf(applied));
        assertEquals("NEW", statusOf(otherEmployer));
        assertFalse(repository.findById(active).orElseThrow().queuedForApplication(),
                "заблокированная вакансия снимается с очереди");
        assertEquals(CompanyBanScope.GLOBAL, repository.findById(active).orElseThrow().banScope());
    }

    @Test
    void resumeScopedBanOnlyTouchesTheListedResumes() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        UUID mine = insert("118000001", "3529", VacancyStatus.NEW, null, false);
        UUID foreign = insert(otherResumeId, "118000002", "3529", VacancyStatus.NEW, null, false, FIRST_SEEN);

        List<UUID> changed = repository.banVacanciesOfEmployer("3529", CompanyBanScope.RESUME,
                Set.of(resumeId), LAST_SEEN);

        assertEquals(List.of(mine), changed);
        assertEquals("NEW", statusOf(foreign));
        assertTrue(repository.banVacanciesOfEmployer("3529", CompanyBanScope.RESUME, Set.of(), LAST_SEEN).isEmpty(),
                "пустой набор резюме означает, что блокировать нечего");
    }

    @Test
    void alreadyBannedVacancyIsReBannedOnlyWhenTheScopeChanges() {
        UUID id = insert("118123456", "3529", VacancyStatus.COMPANY_BANNED, CompanyBanScope.RESUME, false);

        assertTrue(repository.banVacanciesOfEmployer("3529", CompanyBanScope.GLOBAL, null, LAST_SEEN)
                .contains(id), "область блокировки повышается");
        assertEquals(CompanyBanScope.GLOBAL, repository.findById(id).orElseThrow().banScope());

        assertTrue(repository.banVacanciesOfEmployer("3529", CompanyBanScope.GLOBAL, null, LAST_SEEN).isEmpty(),
                "повторное применение той же области ничего не меняет");
    }

    @Test
    void releaseReturnsVacanciesToNewUnlessTheirResumeIsStillBanned() {
        UUID otherResumeId = fixtures.resume(accountId, "0123456789abcdef0123", 20);
        UUID mine = insert("118000001", "3529", VacancyStatus.COMPANY_BANNED, CompanyBanScope.RESUME, false);
        UUID foreign = insert(otherResumeId, "118000002", "3529", VacancyStatus.COMPANY_BANNED,
                CompanyBanScope.RESUME, false, FIRST_SEEN);
        UUID closed = insert("118000003", "3529", VacancyStatus.CLOSED, null, false);

        assertEquals(List.of(foreign), repository.releaseVacanciesOfEmployer("3529", Set.of(resumeId), LAST_SEEN));
        assertEquals("COMPANY_BANNED", statusOf(mine));
        assertEquals("CLOSED", statusOf(closed));

        assertEquals(List.of(mine), repository.releaseVacanciesOfEmployer("3529", Set.of(), LAST_SEEN));
        Vacancy released = repository.findById(mine).orElseThrow();
        assertEquals(VacancyStatus.NEW, released.status());
        assertNull(released.banScope());
        assertFalse(released.queuedForApplication(), "снятие блокировки не возвращает вакансию в очередь");
    }

    @Test
    void labelsAreFullyReplacedAndCanBeCleared() {
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, false);

        repository.replaceLabels(id, List.of("Первая", "Вторая", "Третья"));
        assertEquals(List.of("Первая", "Вторая", "Третья"), repository.findById(id).orElseThrow().labels());

        repository.replaceLabels(id, List.of("Только одна"));
        assertEquals(List.of("Только одна"), repository.findById(id).orElseThrow().labels());

        repository.replaceLabels(id, List.of());
        assertEquals(List.of(), repository.findById(id).orElseThrow().labels());
    }

    @Test
    void applicationOutcomesChangeStateAndDescriptionIndependently() {
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, true);

        repository.markQuestionnaire(id);
        assertEquals(Boolean.TRUE, repository.findById(id).orElseThrow().hasQuestionnaire());

        repository.markApplied(id);
        Vacancy applied = repository.findById(id).orElseThrow();
        assertEquals(VacancyStatus.ALREADY_APPLIED, applied.status());
        assertNull(applied.banScope());
        assertFalse(applied.queuedForApplication());

        assertNull(repository.findText(id).orElseThrow().plainText());
        repository.saveText(id, "Описание вакансии");
        var text = repository.findText(id).orElseThrow();
        assertEquals("Описание вакансии", text.plainText());
        assertTrue(text.textLoadedAt() != null);
        assertTrue(repository.findText(UUID.randomUUID()).isEmpty());
    }

    @Test
    void applicationSourceReportsTheSearchPageOfTheLatestLink() {
        UUID id = insert("118123456", "3529", VacancyStatus.NEW, null, false);
        links.upsert(new VacancySearchLink(UUID.randomUUID(), id, searchId, 250, FIRST_SEEN, FIRST_SEEN));

        var source = repository.applicationSource(id).orElseThrow();

        assertEquals("https://hh.ru/search/vacancy?page=0", source.url());
        assertEquals(2, source.page(), "страница выдачи вычисляется из позиции");
        assertTrue(repository.applicationSource(UUID.randomUUID()).isEmpty());
    }

    @Test
    void queueIsAllowedOnlyForActiveStatuses() {
        UUID id = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () -> repository.insertIfAbsent(
                vacancy(id, resumeId, "118123456", "3529", VacancyStatus.CLOSED, null, true, FIRST_SEEN)));
    }

    @Test
    void archiveClosesQueuedVacancyButPreservesConfirmedApplication() {
        UUID queued = insert("118123457", "3529", VacancyStatus.NEW, null, true);
        UUID applied = insert("118123458", "3529", VacancyStatus.ALREADY_APPLIED, null, false);

        repository.markClosed(queued, LAST_SEEN);
        repository.markClosed(applied, LAST_SEEN);

        Vacancy closed = repository.findById(queued).orElseThrow();
        assertEquals(VacancyStatus.CLOSED, closed.status());
        assertFalse(closed.queuedForApplication());
        assertEquals(VacancyStatus.ALREADY_APPLIED, repository.findById(applied).orElseThrow().status());
    }

    @Test
    void banScopeIsStoredOnlyTogetherWithTheBannedStatus() {
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertIfAbsent(
                vacancy(UUID.randomUUID(), resumeId, "118123456", "3529", VacancyStatus.NEW,
                        CompanyBanScope.GLOBAL, false, FIRST_SEEN)));
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertIfAbsent(
                vacancy(UUID.randomUUID(), resumeId, "118123457", "3529", VacancyStatus.COMPANY_BANNED,
                        null, false, FIRST_SEEN)));
    }
}
