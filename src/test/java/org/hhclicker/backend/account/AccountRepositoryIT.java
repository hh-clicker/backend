package org.hhclicker.backend.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.repository.AccountRepository;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccountRepository на настоящем PostgreSQL. Схему готовит production-changelog Liquibase,
 * поэтому проверяются реальные ограничения: уникальность непустого hh_user_id (с допуском
 * нескольких NULL), check по статусу авторизации и поведение внешних ключей при удалении.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccountRepositoryIT extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-04T09:00:00.654321Z");
    private static final String HH_USER_ID = "hh-user-4815162342";

    @Autowired
    private AccountRepository repository;
    @Autowired
    private JdbcClient jdbc;

    // Общая для всех интеграционных тестов обвязка: здесь нужны только clean/resume/profile.
    private ApplicationDatabaseFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
    }

    private HhAccount account(UUID id, String name, String hhUserId, AuthorizationStatus status, Instant createdAt) {
        return new HhAccount(id, name, hhUserId, status, createdAt, createdAt);
    }

    private UUID insert(String name, String hhUserId, AuthorizationStatus status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        repository.insert(account(id, name, hhUserId, status, createdAt));
        return id;
    }

    @Test
    void insertStoresEveryFieldAndReadsItBackWithMicrosecondPrecision() {
        UUID id = UUID.randomUUID();
        repository.insert(new HhAccount(id, "Основной аккаунт", HH_USER_ID,
                AuthorizationStatus.REQUIRED, CREATED_AT, UPDATED_AT));

        HhAccount stored = repository.findById(id).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals("Основной аккаунт", stored.name());
        assertEquals(HH_USER_ID, stored.hhUserId());
        assertEquals(AuthorizationStatus.REQUIRED, stored.authorizationStatus());
        assertEquals(CREATED_AT, stored.createdAt());
        assertEquals(UPDATED_AT, stored.updatedAt());
    }

    @Test
    void accountWithoutHhUserIdIsStoredAndReadBackAsNull() {
        UUID id = insert("Аккаунт без привязки", null, AuthorizationStatus.UNKNOWN, CREATED_AT);

        assertNull(repository.findById(id).orElseThrow().hhUserId());
    }

    @Test
    void severalAccountsMayStayWithoutHhUserId() {
        insert("Первый", null, AuthorizationStatus.UNKNOWN, CREATED_AT);
        insert("Второй", null, AuthorizationStatus.UNKNOWN, CREATED_AT);

        assertEquals(2, repository.findAll().size(),
                "уникальность hh_user_id не должна запрещать несколько аккаунтов без привязки");
    }

    @Test
    void lookupsByUnknownKeysReturnEmpty() {
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
        assertTrue(repository.findByHhUserId("hh-user-unknown").isEmpty());
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void accountIsFoundByItsHhUserId() {
        UUID id = insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT);
        insert("Другой аккаунт", "hh-user-other", AuthorizationStatus.UNKNOWN, CREATED_AT);

        assertEquals(id, repository.findByHhUserId(HH_USER_ID).orElseThrow().id());
    }

    @Test
    void nonEmptyHhUserIdMustBeUnique() {
        insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT);

        assertThrows(DuplicateKeyException.class,
                () -> insert("Дубликат", HH_USER_ID, AuthorizationStatus.UNKNOWN, CREATED_AT));
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void findAllOrdersByCreationTimeAndThenById() {
        Instant older = Instant.parse("2026-08-01T08:00:00Z");
        UUID newest = UUID.randomUUID();
        UUID sameTimeFirst = UUID.randomUUID();
        UUID sameTimeSecond = UUID.randomUUID();
        List<UUID> sameTimeSorted = Stream.of(sameTimeFirst, sameTimeSecond)
                .sorted(Comparator.comparing(UUID::toString)).toList();

        repository.insert(account(newest, "Новый", null, AuthorizationStatus.UNKNOWN, CREATED_AT));
        repository.insert(account(sameTimeFirst, "Ранний 1", null, AuthorizationStatus.UNKNOWN, older));
        repository.insert(account(sameTimeSecond, "Ранний 2", null, AuthorizationStatus.UNKNOWN, older));

        List<UUID> actual = repository.findAll().stream().map(HhAccount::id).toList();

        assertEquals(3, actual.size());
        assertEquals(newest, actual.getLast(), "самый поздний по created_at идёт последним");
        assertEquals(sameTimeSorted, actual.subList(0, 2), "при равном created_at порядок задаётся id");
    }

    @Test
    void updateTouchesOnlyNameHhUserIdAndUpdatedAt() {
        UUID id = insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT);

        assertTrue(repository.update(new HhAccount(id, "Новое имя", "hh-user-new",
                AuthorizationStatus.REQUIRED, Instant.parse("2020-01-01T00:00:00Z"), UPDATED_AT)));

        HhAccount stored = repository.findById(id).orElseThrow();
        assertEquals("Новое имя", stored.name());
        assertEquals("hh-user-new", stored.hhUserId());
        assertEquals(UPDATED_AT, stored.updatedAt());
        assertEquals(AuthorizationStatus.AUTHORIZED, stored.authorizationStatus(),
                "update профиля не переписывает статус авторизации");
        assertEquals(CREATED_AT, stored.createdAt(), "update профиля не переписывает created_at");
    }

    @Test
    void updateCanClearTheHhUserIdLink() {
        UUID id = insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT);

        assertTrue(repository.update(account(id, "Основной аккаунт", null, AuthorizationStatus.AUTHORIZED, UPDATED_AT)));

        assertNull(repository.findById(id).orElseThrow().hhUserId());
        assertTrue(repository.findByHhUserId(HH_USER_ID).isEmpty());
    }

    @Test
    void updateOfAnUnknownAccountReportsNoChange() {
        assertFalse(repository.update(account(UUID.randomUUID(), "Кто-то", null, AuthorizationStatus.UNKNOWN, CREATED_AT)));
    }

    @Test
    void authorizationStatusUpdateAlsoMovesUpdatedAt() {
        UUID id = insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.UNKNOWN, CREATED_AT);

        assertTrue(repository.updateAuthorizationStatus(id, AuthorizationStatus.AUTHORIZED, UPDATED_AT));

        HhAccount stored = repository.findById(id).orElseThrow();
        assertEquals(AuthorizationStatus.AUTHORIZED, stored.authorizationStatus());
        assertEquals(UPDATED_AT, stored.updatedAt());
        assertEquals("Основной аккаунт", stored.name(), "проверка авторизации не трогает профиль");
        assertEquals(HH_USER_ID, stored.hhUserId());
        assertEquals(CREATED_AT, stored.createdAt());
    }

    @Test
    void authorizationStatusUpdateOfAnUnknownAccountReportsNoChange() {
        assertFalse(repository.updateAuthorizationStatus(UUID.randomUUID(), AuthorizationStatus.REQUIRED, UPDATED_AT));
    }

    @Test
    void deleteRemovesTheAccountAndSecondAttemptReportsNoChange() {
        UUID id = insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT);

        assertTrue(repository.deleteById(id));
        assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.deleteById(id));
        assertFalse(repository.deleteById(UUID.randomUUID()));
    }

    @Test
    void accountWithAResumeCannotBeDeleted() {
        UUID id = insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT);
        fixtures.resume(id, "abcdef0123456789abcd", 20);

        assertThrows(DataIntegrityViolationException.class, () -> repository.deleteById(id));
        assertTrue(repository.findById(id).isPresent());
    }

    @Test
    void browserProfileAloneDoesNotBlockAccountDeletion() {
        UUID id = insert("Основной аккаунт", HH_USER_ID, AuthorizationStatus.AUTHORIZED, CREATED_AT);
        fixtures.profile(id);

        assertTrue(repository.deleteById(id), "браузерный профиль удаляется каскадом вместе с аккаунтом");
        assertEquals(0, jdbc.sql("select count(*) from browser_profile").query(Long.class).single());
    }
}
