package org.hhclicker.backend.vacancy.catalog.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.vacancy.catalog.model.VacancySearchLink;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class VacancySearchLinkRepository {

    private final JdbcClient jdbcClient;

    public VacancySearchLinkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Одна вакансия может быть найдена несколькими поисками, поэтому связь добавляется идемпотентно:
     * повторное обнаружение обновляет только позицию в выдаче HH.
     */
    public void upsert(VacancySearchLink link) {
        jdbcClient.sql("""
                        insert into vacancy_search_link (
                            id, vacancy_id, search_id, position, created_at, updated_at
                        ) values (
                            :id, :vacancyId, :searchId, :position, :createdAt, :updatedAt
                        )
                        on conflict (vacancy_id, search_id) do update
                        set position = excluded.position,
                            updated_at = excluded.updated_at
                        """)
                .param("id", link.id())
                .param("vacancyId", link.vacancyId())
                .param("searchId", link.searchId())
                .param("position", link.position())
                .param("createdAt", toOffsetDateTime(link.createdAt()))
                .param("updatedAt", toOffsetDateTime(link.updatedAt()))
                .update();
    }

    public long countVacanciesBySearchId(UUID searchId) {
        return jdbcClient.sql("""
                        select count(*)
                        from vacancy_search_link link
                        where link.search_id = :searchId
                        """)
                .param("searchId", searchId)
                .query(Long.class)
                .single();
    }

    public long countNewVacanciesBySearchId(UUID searchId) {
        return jdbcClient.sql("""
                        select count(*)
                        from vacancy_search_link link
                        join vacancy v on v.id = link.vacancy_id
                        where link.search_id = :searchId and v.status = 'NEW'
                        """)
                .param("searchId", searchId)
                .query(Long.class)
                .single();
    }
}
