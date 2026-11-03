package org.hhclicker.backend.vacancy.favorites.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.vacancy.favorites.model.VacancyFavorites;

import java.util.UUID;

@Repository
public class VacancyFavoritesRepository {
    private final JdbcClient jdbc;

    public VacancyFavoritesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public VacancyFavorites findByAccount(UUID accountId) {
        return new VacancyFavorites(accountId,
                jdbc.sql("select hh_vacancy_id from vacancy_favorite where account_id = :accountId order by hh_vacancy_id")
                        .param("accountId", accountId).query(String.class).list(),
                jdbc.sql("select employer_id from employer_favorite where account_id = :accountId order by employer_id")
                        .param("accountId", accountId).query(String.class).list());
    }

    public void set(UUID accountId, String targetId, boolean employer, boolean favorite) {
        String table = employer ? "employer_favorite" : "vacancy_favorite";
        String column = employer ? "employer_id" : "hh_vacancy_id";
        String sql = favorite
                ? "insert into %s (account_id, %s) values (:accountId, :targetId) on conflict do nothing".formatted(table, column)
                : "delete from %s where account_id = :accountId and %s = :targetId".formatted(table, column);
        jdbc.sql(sql).param("accountId", accountId).param("targetId", targetId).update();
    }
}
