package org.hhclicker.backend.resume;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResumeRepository на настоящем PostgreSQL. Схему готовит production-changelog Liquibase,
 * поэтому проверяются реальные ограничения: уникальность hh_resume_id, check по лимитам и паузам,
 * RESTRICT на удаление резюме со связанными данными.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ResumeRepositoryIT extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant TEXT_LOADED_AT = Instant.parse("2026-09-02T10:30:00Z");
    private static final String HASH = "abcdef0123456789abcd";
    private static final String OTHER_HASH = "0123456789abcdef0123";
    private static final String PLAIN_TEXT = "Java-разработчик\n\nОпыт: сервисы на Spring";

    @Autowired
    private ResumeRepository repository;
    @Autowired
    private JdbcClient jdbc;

    // Класс фикстур общий для всех интеграционных тестов: здесь нужны только clean/account/search.
    private ApplicationDatabaseFixtures fixtures;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        accountId = fixtures.account("Основной аккаунт");
    }

    private HhResume resume(UUID id, UUID owner, String hash, String name, ResumeAutomationSettings settings,
                            Instant createdAt, String plainText, Instant textLoadedAt) {
        return new HhResume(id, owner, hash, name, settings, createdAt, createdAt, plainText, textLoadedAt);
    }

    private HhResume defaultResume(UUID id, String hash) {
        return resume(id, accountId, hash, "Java-разработчик", ResumeAutomationSettings.defaults(),
                CREATED_AT, PLAIN_TEXT, TEXT_LOADED_AT);
    }

    @Test
    void insertStoresEverySettingTextAndTimestamp() {
        UUID id = UUID.randomUUID();
        var settings = new ResumeAutomationSettings("Обычное письмо", 42, 20, 60, false, AiLetterSettings.defaults());

        assertTrue(repository.insertIfAbsent(resume(id, accountId, HASH, "Java-разработчик", settings,
                CREATED_AT, PLAIN_TEXT, TEXT_LOADED_AT)));

        HhResume stored = repository.findById(id).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals(accountId, stored.accountId());
        assertEquals(HASH, stored.hhResumeId());
        assertEquals("Java-разработчик", stored.name());
        assertEquals("Обычное письмо", stored.settings().coverLetter());
        assertEquals(42, stored.settings().dailyLimit());
        assertEquals(20, stored.settings().pauseFromSeconds());
        assertEquals(60, stored.settings().pauseToSeconds());
        assertFalse(stored.settings().skipQuestionnaires());
        assertEquals(CREATED_AT, stored.createdAt());
        assertEquals(CREATED_AT, stored.updatedAt());
        assertEquals(PLAIN_TEXT, stored.plainText());
        assertEquals(TEXT_LOADED_AT, stored.textLoadedAt());
    }

    @Test
    void insertLeavesGenerationColumnsAtDatabaseDefaultsAndReadsThemBackAsDefaults() {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(defaultResume(id, HASH));

        assertTrue(jdbc.sql("select ai_letter_template is null from hh_resume where id = :id")
                        .param("id", id).query(Boolean.class).single(),
                "insertIfAbsent не пишет колонки генерации — в БД остаётся NULL");
        assertEquals(AiLetterSettings.defaults(), repository.findById(id).orElseThrow().settings().aiLetter(),
                "NULL-шаблон при чтении нормализуется в шаблон по умолчанию");
    }

    @Test
    void insertIgnoresAConflictOnHhResumeIdAndKeepsTheStoredRow() {
        UUID first = UUID.randomUUID();
        repository.insertIfAbsent(defaultResume(first, HASH));
        UUID otherAccountId = fixtures.account("Запасной аккаунт");

        boolean inserted = repository.insertIfAbsent(resume(UUID.randomUUID(), otherAccountId, HASH,
                "Другое название", ResumeAutomationSettings.defaults(), CREATED_AT, "Другой текст", TEXT_LOADED_AT));

        assertFalse(inserted);
        assertEquals(1, count());
        HhResume stored = repository.findByHhResumeId(HASH).orElseThrow();
        assertEquals(first, stored.id());
        assertEquals(accountId, stored.accountId());
        assertEquals("Java-разработчик", stored.name());
    }

    @Test
    void lookupsByUnknownKeysReturnEmpty() {
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
        assertTrue(repository.findByHhResumeId("ffffffffffffffffffff").isEmpty());
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void findAllOrdersByCreationTimeAndThenById() {
        Instant older = Instant.parse("2026-08-01T08:00:00Z");
        UUID newest = UUID.randomUUID();
        UUID sameTimeFirst = UUID.randomUUID();
        UUID sameTimeSecond = UUID.randomUUID();
        List<UUID> sameTimeSorted = List.of(sameTimeFirst, sameTimeSecond).stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();

        repository.insertIfAbsent(resume(newest, accountId, HASH, "Новое", ResumeAutomationSettings.defaults(),
                CREATED_AT, PLAIN_TEXT, TEXT_LOADED_AT));
        repository.insertIfAbsent(resume(sameTimeFirst, accountId, OTHER_HASH, "Раннее 1",
                ResumeAutomationSettings.defaults(), older, PLAIN_TEXT, TEXT_LOADED_AT));
        repository.insertIfAbsent(resume(sameTimeSecond, accountId, "aaaaaaaaaaaaaaaaaaaa", "Раннее 2",
                ResumeAutomationSettings.defaults(), older, PLAIN_TEXT, TEXT_LOADED_AT));

        List<UUID> actual = repository.findAll().stream().map(HhResume::id).toList();

        assertEquals(3, actual.size());
        assertEquals(newest, actual.getLast(), "самое позднее по created_at идёт последним");
        assertEquals(sameTimeSorted, actual.subList(0, 2), "при равном created_at порядок задаётся id");
    }

    @Test
    void findAllByAccountIdReturnsOnlyTheRequestedAccount() {
        UUID otherAccountId = fixtures.account("Запасной аккаунт");
        UUID mine = UUID.randomUUID();
        repository.insertIfAbsent(defaultResume(mine, HASH));
        repository.insertIfAbsent(resume(UUID.randomUUID(), otherAccountId, OTHER_HASH, "Чужое",
                ResumeAutomationSettings.defaults(), CREATED_AT, PLAIN_TEXT, TEXT_LOADED_AT));

        assertEquals(List.of(mine), repository.findAllByAccountId(accountId).stream().map(HhResume::id).toList());
        assertEquals(2, repository.findAll().size());
        assertTrue(repository.findAllByAccountId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void updateSettingsPersistsGenerationBlockAndKeepsIdentityFields() {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(defaultResume(id, HASH));
        Instant updatedAt = Instant.parse("2026-09-05T12:00:00Z");
        var ai = new AiLetterSettings(true, "Здравствуйте!\n#AI_CHANGE#\nСпасибо!", 1200);
        var settings = new ResumeAutomationSettings("Новое письмо", 7, 15, 45, false, ai);

        assertTrue(repository.updateSettings(new HhResume(id, accountId, HASH, "Игнорируемое название",
                settings, CREATED_AT, updatedAt, "Игнорируемый текст", null)));

        HhResume stored = repository.findById(id).orElseThrow();
        assertEquals(settings, stored.settings());
        assertEquals(updatedAt, stored.updatedAt());
        assertEquals("Java-разработчик", stored.name(), "updateSettings не меняет название");
        assertEquals(HASH, stored.hhResumeId());
        assertEquals(accountId, stored.accountId());
        assertEquals(CREATED_AT, stored.createdAt());
        assertEquals(PLAIN_TEXT, stored.plainText(), "снимок текста резюме переживает смену настроек");
        assertEquals(TEXT_LOADED_AT, stored.textLoadedAt());
    }

    @Test
    void updateSettingsReportsMissingResume() {
        assertFalse(repository.updateSettings(defaultResume(UUID.randomUUID(), HASH)));
    }

    @Test
    void databaseRejectsSettingsThatViolateTheAutomationCheckConstraint() {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(defaultResume(id, HASH));
        var inverted = new ResumeAutomationSettings("Письмо", 20, 60, 30, true, AiLetterSettings.defaults());

        assertThrows(DataIntegrityViolationException.class, () -> repository.updateSettings(
                new HhResume(id, accountId, HASH, "Java-разработчик", inverted, CREATED_AT, CREATED_AT, PLAIN_TEXT, TEXT_LOADED_AT)));
    }

    @Test
    void resumeWithoutTextSnapshotIsStoredAndReadBackAsNull() {
        UUID id = UUID.randomUUID();

        assertTrue(repository.insertIfAbsent(resume(id, accountId, HASH, "Java-разработчик",
                ResumeAutomationSettings.defaults(), CREATED_AT, null, null)));

        HhResume stored = repository.findById(id).orElseThrow();
        assertNull(stored.plainText());
        assertNull(stored.textLoadedAt());
    }

    @Test
    void timestampsRoundTripWithMicrosecondPrecision() {
        UUID id = UUID.randomUUID();
        Instant precise = Instant.parse("2026-09-04T09:00:00.123456Z");

        repository.insertIfAbsent(resume(id, accountId, HASH, "Java-разработчик",
                ResumeAutomationSettings.defaults(), precise, PLAIN_TEXT, precise));

        HhResume stored = repository.findById(id).orElseThrow();
        assertEquals(precise, stored.createdAt());
        assertEquals(precise, stored.textLoadedAt());
    }

    @Test
    void deleteRemovesTheResumeAndReportsAnUnknownId() {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(defaultResume(id, HASH));

        assertTrue(repository.deleteById(id));
        assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.deleteById(id));
        assertFalse(repository.deleteById(UUID.randomUUID()));
    }

    @Test
    void deleteIsRejectedWhileDependentSearchesExist() {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(defaultResume(id, HASH));
        fixtures.search(id, HASH);

        assertThrows(DataIntegrityViolationException.class, () -> repository.deleteById(id));
        assertTrue(repository.findById(id).isPresent());
    }

    private long count() {
        return jdbc.sql("select count(*) from hh_resume").query(Long.class).single();
    }
}
