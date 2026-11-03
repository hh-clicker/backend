package org.hhclicker.backend.vacancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;
import org.hhclicker.backend.support.PostgresIntegrationTest;
import org.hhclicker.backend.vacancy.favorites.model.VacancyFavorites;
import org.hhclicker.backend.vacancy.favorites.repository.VacancyFavoritesRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Локальные звёздочки: раздельные таблицы вакансий и работодателей, идемпотентная запись.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VacancyFavoritesRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private VacancyFavoritesRepository repository;
    @Autowired
    private JdbcClient jdbc;

    private ApplicationDatabaseFixtures fixtures;
    private UUID accountId;
    private UUID otherAccountId;

    @BeforeEach
    void setUp() {
        fixtures = new ApplicationDatabaseFixtures(jdbc);
        fixtures.clean();
        accountId = fixtures.account("Основной аккаунт");
        otherAccountId = fixtures.account("Запасной аккаунт");
    }

    @Test
    void accountWithoutFavoritesReturnsEmptyLists() {
        VacancyFavorites favorites = repository.findByAccount(accountId);

        assertEquals(accountId, favorites.accountId());
        assertTrue(favorites.vacancyIds().isEmpty());
        assertTrue(favorites.employerIds().isEmpty());
    }

    @Test
    void vacancyAndEmployerFavoritesAreStoredSeparately() {
        repository.set(accountId, "118123456", false, true);
        repository.set(accountId, "3529", true, true);

        VacancyFavorites favorites = repository.findByAccount(accountId);
        assertEquals(List.of("118123456"), favorites.vacancyIds());
        assertEquals(List.of("3529"), favorites.employerIds());
    }

    @Test
    void favoritesAreReturnedInAStableOrder() {
        repository.set(accountId, "118000003", false, true);
        repository.set(accountId, "118000001", false, true);
        repository.set(accountId, "118000002", false, true);
        repository.set(accountId, "9999", true, true);
        repository.set(accountId, "3529", true, true);

        VacancyFavorites favorites = repository.findByAccount(accountId);
        assertEquals(List.of("118000001", "118000002", "118000003"), favorites.vacancyIds());
        assertEquals(List.of("3529", "9999"), favorites.employerIds());
    }

    @Test
    void repeatedStarIsIdempotent() {
        repository.set(accountId, "118123456", false, true);
        repository.set(accountId, "118123456", false, true);

        assertEquals(List.of("118123456"), repository.findByAccount(accountId).vacancyIds());
        assertEquals(1, jdbc.sql("select count(*) from vacancy_favorite").query(Long.class).single());
    }

    @Test
    void removingAStarIsIdempotentToo() {
        repository.set(accountId, "118123456", false, true);

        repository.set(accountId, "118123456", false, false);
        repository.set(accountId, "118123456", false, false);
        repository.set(accountId, "118999999", false, false);

        assertTrue(repository.findByAccount(accountId).vacancyIds().isEmpty());
    }

    @Test
    void removingAVacancyStarDoesNotTouchTheEmployerStar() {
        repository.set(accountId, "3529", true, true);
        repository.set(accountId, "3529", false, true);

        repository.set(accountId, "3529", false, false);

        VacancyFavorites favorites = repository.findByAccount(accountId);
        assertTrue(favorites.vacancyIds().isEmpty());
        assertEquals(List.of("3529"), favorites.employerIds(), "таблицы вакансий и работодателей независимы");
    }

    @Test
    void favoritesOfDifferentAccountsDoNotMix() {
        repository.set(accountId, "118123456", false, true);
        repository.set(otherAccountId, "118999999", false, true);
        repository.set(otherAccountId, "3529", true, true);

        assertEquals(List.of("118123456"), repository.findByAccount(accountId).vacancyIds());
        assertTrue(repository.findByAccount(accountId).employerIds().isEmpty());
        assertEquals(List.of("118999999"), repository.findByAccount(otherAccountId).vacancyIds());
        assertEquals(List.of("3529"), repository.findByAccount(otherAccountId).employerIds());
    }

    @Test
    void favoritesDisappearTogetherWithTheirAccount() {
        repository.set(accountId, "118123456", false, true);
        repository.set(accountId, "3529", true, true);

        jdbc.sql("delete from hh_account where id = :id").param("id", accountId).update();

        assertEquals(0, jdbc.sql("select count(*) from vacancy_favorite").query(Long.class).single());
        assertEquals(0, jdbc.sql("select count(*) from employer_favorite").query(Long.class).single());
    }
}
