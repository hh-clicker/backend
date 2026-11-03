package org.hhclicker.backend.search.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.model.VacancySearch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SearchRepository {

    private static final String SEARCH_COLUMNS = """
            id, resume_id, name, source_url, source_hash, pages_limit, status,
            new_vacancies, total_vacancies, last_scanned_at, last_error,
            scan_started_at, scan_current_page, scan_total_pages, scan_collected, scan_total_results,
            created_at, updated_at
            """;

    private final JdbcClient jdbcClient;

    public SearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static VacancySearch mapSearch(ResultSet resultSet, int rowNumber) throws SQLException {
        return new VacancySearch(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("resume_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("source_url"),
                resultSet.getString("source_hash"),
                resultSet.getInt("pages_limit"),
                SearchStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("new_vacancies"),
                resultSet.getLong("total_vacancies"),
                toInstant(resultSet, "last_scanned_at"),
                resultSet.getString("last_error"),
                toInstant(resultSet, "scan_started_at"),
                nullableInteger(resultSet, "scan_current_page"),
                nullableInteger(resultSet, "scan_total_pages"),
                nullableInteger(resultSet, "scan_collected"),
                nullableInteger(resultSet, "scan_total_results"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static Instant toInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, Integer.class);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public List<VacancySearch> findAll() {
        return jdbcClient.sql("""
                        select %s
                        from vacancy_search
                        order by created_at desc, id
                        """.formatted(SEARCH_COLUMNS))
                .query(SearchRepository::mapSearch)
                .list();
    }

    public List<VacancySearch> findAllByResumeId(UUID resumeId) {
        return jdbcClient.sql("""
                        select %s
                        from vacancy_search
                        where resume_id = :resumeId
                        order by created_at desc, id
                        """.formatted(SEARCH_COLUMNS))
                .param("resumeId", resumeId)
                .query(SearchRepository::mapSearch)
                .list();
    }

    public Optional<VacancySearch> findById(UUID id) {
        return jdbcClient.sql("""
                        select %s
                        from vacancy_search
                        where id = :id
                        """.formatted(SEARCH_COLUMNS))
                .param("id", id)
                .query(SearchRepository::mapSearch)
                .optional();
    }

    public Optional<VacancySearch> findByResumeIdAndSourceHash(UUID resumeId, String sourceHash) {
        return jdbcClient.sql("""
                        select %s
                        from vacancy_search
                        where resume_id = :resumeId and source_hash = :sourceHash
                        """.formatted(SEARCH_COLUMNS))
                .param("resumeId", resumeId)
                .param("sourceHash", sourceHash)
                .query(SearchRepository::mapSearch)
                .optional();
    }

    public Optional<VacancySearch> findByIdForUpdate(UUID id) {
        // Transaction-local: do not let a blocked search pin command handling indefinitely.
        jdbcClient.sql("select set_config('lock_timeout', '5s', true)").query(String.class).single();
        return jdbcClient.sql("select %s from vacancy_search where id = :id for update".formatted(SEARCH_COLUMNS))
                .param("id", id).query(SearchRepository::mapSearch).optional();
    }

    public long countByResumeId(UUID resumeId) {
        return jdbcClient.sql("select count(*) from vacancy_search where resume_id = :resumeId")
                .param("resumeId", resumeId)
                .query(Long.class)
                .single();
    }

    public void insert(VacancySearch search) {
        jdbcClient.sql("""
                        insert into vacancy_search (
                            id, resume_id, name, source_url, source_hash, pages_limit, status,
                            new_vacancies, total_vacancies, last_scanned_at, last_error,
                            scan_started_at, scan_current_page, scan_total_pages, scan_collected, scan_total_results,
                            created_at, updated_at
                        ) values (
                            :id, :resumeId, :name, :sourceUrl, :sourceHash, :pagesLimit, :status,
                            :newVacancies, :totalVacancies, :lastScannedAt, :lastError,
                            :scanStartedAt, :scanCurrentPage, :scanTotalPages, :scanCollected, :scanTotalResults,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("id", search.id())
                .param("resumeId", search.resumeId())
                .param("name", search.name())
                .param("sourceUrl", search.sourceUrl())
                .param("sourceHash", search.sourceHash())
                .param("pagesLimit", search.pagesLimit())
                .param("status", search.status().name())
                .param("newVacancies", search.newVacancies())
                .param("totalVacancies", search.totalVacancies())
                .param("lastScannedAt", toOffsetDateTime(search.lastScannedAt()))
                .param("lastError", search.lastError())
                .param("scanStartedAt", toOffsetDateTime(search.scanStartedAt()))
                .param("scanCurrentPage", search.scanCurrentPage())
                .param("scanTotalPages", search.scanTotalPages())
                .param("scanCollected", search.scanCollected())
                .param("scanTotalResults", search.scanTotalResults())
                .param("createdAt", toOffsetDateTime(search.createdAt()))
                .param("updatedAt", toOffsetDateTime(search.updatedAt()))
                .update();
    }

    public boolean updateDefinition(VacancySearch search) {
        return jdbcClient.sql("""
                        update vacancy_search
                        set name = :name, source_url = :sourceUrl, source_hash = :sourceHash,
                            pages_limit = :pagesLimit, status = :status, last_error = null,
                            scan_started_at = null, scan_current_page = null, scan_total_pages = null,
                            scan_collected = null, scan_total_results = null, updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", search.id()).param("name", search.name())
                .param("sourceUrl", search.sourceUrl()).param("sourceHash", search.sourceHash())
                .param("pagesLimit", search.pagesLimit()).param("status", search.status().name())
                .param("updatedAt", toOffsetDateTime(search.updatedAt())).update() == 1;
    }

    public boolean updateStatus(VacancySearch search) {
        return jdbcClient.sql("""
                        update vacancy_search
                        set status = :status,
                            last_error = :lastError,
                            scan_started_at = :scanStartedAt,
                            scan_current_page = :scanCurrentPage,
                            scan_total_pages = :scanTotalPages,
                            scan_collected = :scanCollected,
                            scan_total_results = :scanTotalResults,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", search.id())
                .param("status", search.status().name())
                .param("lastError", search.lastError())
                .param("scanStartedAt", toOffsetDateTime(search.scanStartedAt()))
                .param("scanCurrentPage", search.scanCurrentPage())
                .param("scanTotalPages", search.scanTotalPages())
                .param("scanCollected", search.scanCollected())
                .param("scanTotalResults", search.scanTotalResults())
                .param("updatedAt", toOffsetDateTime(search.updatedAt()))
                .update() == 1;
    }

    public boolean updateAfterScan(VacancySearch search) {
        return jdbcClient.sql("""
                        update vacancy_search
                        set status = :status,
                            last_scanned_at = :lastScannedAt,
                            last_error = :lastError,
                            scan_started_at = :scanStartedAt,
                            scan_current_page = :scanCurrentPage,
                            scan_total_pages = :scanTotalPages,
                            scan_collected = :scanCollected,
                            scan_total_results = :scanTotalResults,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", search.id())
                .param("status", search.status().name())
                .param("lastScannedAt", toOffsetDateTime(search.lastScannedAt()))
                .param("lastError", search.lastError())
                .param("scanStartedAt", toOffsetDateTime(search.scanStartedAt()))
                .param("scanCurrentPage", search.scanCurrentPage())
                .param("scanTotalPages", search.scanTotalPages())
                .param("scanCollected", search.scanCollected())
                .param("scanTotalResults", search.scanTotalResults())
                .param("updatedAt", toOffsetDateTime(search.updatedAt()))
                .update() == 1;
    }

    /**
     * Фиксирует успешный приём результатов поиска вместе с пересчитанными счётчиками вакансий.
     */
    public boolean updateAfterIngestion(VacancySearch search) {
        return jdbcClient.sql("""
                        update vacancy_search
                        set status = :status,
                            new_vacancies = :newVacancies,
                            total_vacancies = :totalVacancies,
                            last_scanned_at = :lastScannedAt,
                            last_error = null,
                            scan_started_at = null,
                            scan_current_page = null,
                            scan_total_pages = null,
                            scan_collected = null,
                            scan_total_results = null,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", search.id())
                .param("status", search.status().name())
                .param("newVacancies", search.newVacancies())
                .param("totalVacancies", search.totalVacancies())
                .param("lastScannedAt", toOffsetDateTime(search.lastScannedAt()))
                .param("updatedAt", toOffsetDateTime(search.updatedAt()))
                .update() == 1;
    }

    public boolean updateScanProgress(VacancySearch search) {
        return jdbcClient.sql("""
                        update vacancy_search
                        set scan_current_page = :scanCurrentPage,
                            scan_total_pages = :scanTotalPages,
                            scan_collected = :scanCollected,
                            scan_total_results = :scanTotalResults,
                            updated_at = :updatedAt
                        where id = :id and status = 'SCANNING'
                        """)
                .param("id", search.id())
                .param("scanCurrentPage", search.scanCurrentPage())
                .param("scanTotalPages", search.scanTotalPages())
                .param("scanCollected", search.scanCollected())
                .param("scanTotalResults", search.scanTotalResults())
                .param("updatedAt", toOffsetDateTime(search.updatedAt()))
                .update() == 1;
    }

    public boolean deleteById(UUID id) {
        return jdbcClient.sql("delete from vacancy_search where id = :id")
                .param("id", id)
                .update() == 1;
    }
}
