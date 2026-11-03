package org.hhclicker.backend.vacancy.stoplist.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.vacancy.catalog.model.CompanyBanScope;
import org.hhclicker.backend.vacancy.stoplist.model.CompanyBan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CompanyBanRepository {

    private static final String BAN_COLUMNS = """
            id, employer_id, employer_url, company_name, scope, resume_id,
            source_vacancy_id, created_at, updated_at
            """;

    private final JdbcClient jdbcClient;

    public CompanyBanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static CompanyBan mapBan(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CompanyBan(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("employer_id"),
                resultSet.getString("employer_url"),
                resultSet.getString("company_name"),
                CompanyBanScope.valueOf(resultSet.getString("scope")),
                resultSet.getObject("resume_id", UUID.class),
                resultSet.getObject("source_vacancy_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public List<CompanyBan> findAll() {
        return jdbcClient.sql("""
                        select %s
                        from company_ban
                        order by created_at desc, id
                        """.formatted(BAN_COLUMNS))
                .query(CompanyBanRepository::mapBan)
                .list();
    }

    /**
     * Для выбранного резюме видны его собственные блокировки и все глобальные.
     */
    public List<CompanyBan> findAllVisibleForResume(UUID resumeId) {
        return jdbcClient.sql("""
                        select %s
                        from company_ban
                        where scope = 'GLOBAL' or resume_id = :resumeId
                        order by created_at desc, id
                        """.formatted(BAN_COLUMNS))
                .param("resumeId", resumeId)
                .query(CompanyBanRepository::mapBan)
                .list();
    }

    public Optional<CompanyBan> findById(UUID id) {
        return jdbcClient.sql("""
                        select %s
                        from company_ban
                        where id = :id
                        """.formatted(BAN_COLUMNS))
                .param("id", id)
                .query(CompanyBanRepository::mapBan)
                .optional();
    }

    public List<CompanyBan> findAllByEmployerId(String employerId) {
        return jdbcClient.sql("""
                        select %s
                        from company_ban
                        where employer_id = :employerId
                        order by created_at, id
                        """.formatted(BAN_COLUMNS))
                .param("employerId", employerId)
                .query(CompanyBanRepository::mapBan)
                .list();
    }

    public List<CompanyBan> findApplicable(Collection<String> employerIds, UUID resumeId) {
        if (employerIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select %s
                        from company_ban
                        where employer_id in (:employerIds)
                          and (scope = 'GLOBAL' or resume_id = :resumeId)
                        """.formatted(BAN_COLUMNS))
                .param("employerIds", employerIds)
                .param("resumeId", resumeId)
                .query(CompanyBanRepository::mapBan)
                .list();
    }

    public long countDistinctVisibleEmployers(UUID resumeId) {
        return jdbcClient.sql("""
                        select count(distinct employer_id)
                        from company_ban
                        where scope = 'GLOBAL' or resume_id = :resumeId
                        """)
                .param("resumeId", resumeId)
                .query(Long.class)
                .single();
    }

    public void insert(CompanyBan ban) {
        jdbcClient.sql("""
                        insert into company_ban (
                            id, employer_id, employer_url, company_name, scope, resume_id,
                            source_vacancy_id, created_at, updated_at
                        ) values (
                            :id, :employerId, :employerUrl, :companyName, :scope, :resumeId,
                            :sourceVacancyId, :createdAt, :updatedAt
                        )
                        """)
                .param("id", ban.id())
                .param("employerId", ban.employerId())
                .param("employerUrl", ban.employerUrl())
                .param("companyName", ban.companyName())
                .param("scope", ban.scope().name())
                .param("resumeId", ban.resumeId())
                .param("sourceVacancyId", ban.sourceVacancyId())
                .param("createdAt", toOffsetDateTime(ban.createdAt()))
                .param("updatedAt", toOffsetDateTime(ban.updatedAt()))
                .update();
    }

    public boolean updateScope(CompanyBan ban) {
        return jdbcClient.sql("""
                        update company_ban
                        set scope = :scope,
                            resume_id = :resumeId,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", ban.id())
                .param("scope", ban.scope().name())
                .param("resumeId", ban.resumeId())
                .param("updatedAt", toOffsetDateTime(ban.updatedAt()))
                .update() == 1;
    }

    public boolean deleteById(UUID id) {
        return jdbcClient.sql("delete from company_ban where id = :id")
                .param("id", id)
                .update() == 1;
    }
}
