package org.hhclicker.backend.browser.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.browser.model.BrowserProfile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BrowserProfileRepository {

    private static final String COLUMNS = """
            id, account_id, created_at, updated_at
            """;

    private final JdbcClient jdbcClient;

    public BrowserProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static java.util.Map<String, Object> parameters(BrowserProfile profile) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("id", profile.id());
        values.put("accountId", profile.accountId());
        values.put("createdAt", OffsetDateTime.ofInstant(profile.createdAt(), ZoneOffset.UTC));
        values.put("updatedAt", OffsetDateTime.ofInstant(profile.updatedAt(), ZoneOffset.UTC));
        return values;
    }

    private static BrowserProfile map(ResultSet rs, int row) throws SQLException {
        return new BrowserProfile(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    public Optional<BrowserProfile> findByAccountId(UUID accountId) {
        return jdbcClient.sql("select %s from browser_profile where account_id = :accountId".formatted(COLUMNS))
                .param("accountId", accountId).query(BrowserProfileRepository::map).optional();
    }

    public Optional<BrowserProfile> findById(UUID id) {
        return jdbcClient.sql("select %s from browser_profile where id = :id".formatted(COLUMNS))
                .param("id", id).query(BrowserProfileRepository::map).optional();
    }

    public void insert(BrowserProfile profile) {
        jdbcClient.sql("""
                insert into browser_profile (
                    id, account_id, created_at, updated_at
                ) values (
                    :id, :accountId, :createdAt, :updatedAt
                )
                """).params(parameters(profile)).update();
    }
}
