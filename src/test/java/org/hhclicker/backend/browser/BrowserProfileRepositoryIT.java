package org.hhclicker.backend.browser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.repository.BrowserProfileRepository;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BrowserProfileRepository на настоящем PostgreSQL: уникальность профиля на аккаунт,
 * внешний ключ и каскад при удалении аккаунта.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BrowserProfileRepositoryIT extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-04T09:00:00.654321Z");

    @Autowired
    private BrowserProfileRepository repository;
    @Autowired
    private JdbcClient jdbc;

    private ApplicationDatabaseFixtures fixtures;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        accountId = fixtures.account("Основной аккаунт");
    }

    private UUID insert(UUID owner, Instant createdAt, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        repository.insert(new BrowserProfile(id, owner, createdAt, updatedAt));
        return id;
    }

    @Test
    void profileIsStoredAndReadBackWithMicrosecondPrecision() {
        UUID id = insert(accountId, CREATED_AT, UPDATED_AT);

        BrowserProfile stored = repository.findById(id).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals(accountId, stored.accountId());
        assertEquals(CREATED_AT, stored.createdAt());
        assertEquals(UPDATED_AT, stored.updatedAt());
    }

    @Test
    void profileIsAlsoReachableByItsAccount() {
        UUID id = insert(accountId, CREATED_AT, UPDATED_AT);

        BrowserProfile stored = repository.findByAccountId(accountId).orElseThrow();
        assertEquals(id, stored.id());
        assertEquals(UPDATED_AT, stored.updatedAt());
    }

    @Test
    void unknownKeysReturnNothing() {
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
        assertTrue(repository.findByAccountId(UUID.randomUUID()).isEmpty());
        assertTrue(repository.findByAccountId(accountId).isEmpty(), "аккаунт без профиля — обычное состояние");
    }

    @Test
    void oneAccountCannotOwnTwoBrowserProfiles() {
        insert(accountId, CREATED_AT, UPDATED_AT);

        assertThrows(DuplicateKeyException.class, () -> insert(accountId, CREATED_AT, UPDATED_AT));

        assertEquals(1, jdbc.sql("select count(*) from browser_profile").query(Long.class).single());
    }

    @Test
    void differentAccountsKeepTheirOwnProfiles() {
        UUID otherAccountId = fixtures.account("Запасной аккаунт");
        UUID mine = insert(accountId, CREATED_AT, UPDATED_AT);
        UUID foreign = insert(otherAccountId, CREATED_AT, UPDATED_AT);

        assertEquals(mine, repository.findByAccountId(accountId).orElseThrow().id());
        assertEquals(foreign, repository.findByAccountId(otherAccountId).orElseThrow().id());
        assertEquals(2, jdbc.sql("select count(*) from browser_profile").query(Long.class).single());
    }

    @Test
    void profileOfAnAccountThatDoesNotExistIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insert(UUID.randomUUID(), CREATED_AT, UPDATED_AT));
    }

    @Test
    void deletingTheAccountRemovesItsBrowserProfile() {
        UUID id = insert(accountId, CREATED_AT, UPDATED_AT);

        jdbc.sql("delete from hh_account where id = :id").param("id", accountId).update();

        assertTrue(repository.findById(id).isEmpty(), "профиль удаляется каскадом вместе с аккаунтом");
        assertEquals(0, jdbc.sql("select count(*) from browser_profile").query(Long.class).single());
    }

    @Test
    void mandatoryColumnsAreEnforcedByTheSchema() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                insert into browser_profile (id, account_id, created_at, updated_at)
                values (:id, null, now(), now())
                """).param("id", UUID.randomUUID()).update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                insert into browser_profile (id, account_id, created_at, updated_at)
                values (:id, :account, null, now())
                """).param("id", UUID.randomUUID()).param("account", accountId).update());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                insert into browser_profile (id, account_id, created_at, updated_at)
                values (:id, :account, now(), null)
                """).param("id", UUID.randomUUID()).param("account", accountId).update());
    }
}
