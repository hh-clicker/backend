package org.hhclicker.backend.account.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final String ACCOUNT_COLUMNS = """
            id, name, hh_user_id, authorization_status, created_at, updated_at
            """;

    private final JdbcClient jdbcClient;

    public AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static HhAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        return new HhAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("hh_user_id"),
                AuthorizationStatus.valueOf(resultSet.getString("authorization_status")),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    public List<HhAccount> findAll() {
        return jdbcClient.sql("""
                        select %s
                        from hh_account
                        order by created_at, id
                        """.formatted(ACCOUNT_COLUMNS))
                .query(AccountRepository::mapAccount)
                .list();
    }

    public Optional<HhAccount> findById(UUID id) {
        return jdbcClient.sql("""
                        select %s
                        from hh_account
                        where id = :id
                        """.formatted(ACCOUNT_COLUMNS))
                .param("id", id)
                .query(AccountRepository::mapAccount)
                .optional();
    }

    public Optional<HhAccount> findByHhUserId(String hhUserId) {
        return jdbcClient.sql("""
                        select %s
                        from hh_account
                        where hh_user_id = :hhUserId
                        """.formatted(ACCOUNT_COLUMNS))
                .param("hhUserId", hhUserId)
                .query(AccountRepository::mapAccount)
                .optional();
    }

    public void insert(HhAccount account) {
        jdbcClient.sql("""
                        insert into hh_account (
                            id, name, hh_user_id, authorization_status, created_at, updated_at
                        ) values (
                            :id, :name, :hhUserId, :authorizationStatus, :createdAt, :updatedAt
                        )
                        """)
                .param("id", account.id())
                .param("name", account.name())
                .param("hhUserId", account.hhUserId())
                .param("authorizationStatus", account.authorizationStatus().name())
                .param("createdAt", OffsetDateTime.ofInstant(account.createdAt(), java.time.ZoneOffset.UTC))
                .param("updatedAt", OffsetDateTime.ofInstant(account.updatedAt(), java.time.ZoneOffset.UTC))
                .update();
    }

    public boolean update(HhAccount account) {
        return jdbcClient.sql("""
                        update hh_account
                        set name = :name,
                            hh_user_id = :hhUserId,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", account.id())
                .param("name", account.name())
                .param("hhUserId", account.hhUserId())
                .param("updatedAt", OffsetDateTime.ofInstant(account.updatedAt(), java.time.ZoneOffset.UTC))
                .update() == 1;
    }

    public boolean updateAuthorizationStatus(UUID id, AuthorizationStatus status, Instant checkedAt) {
        return jdbcClient.sql("""
                        update hh_account
                        set authorization_status = :status, updated_at = :checkedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("checkedAt", OffsetDateTime.ofInstant(checkedAt, java.time.ZoneOffset.UTC))
                .update() == 1;
    }

    public boolean deleteById(UUID id) {
        return jdbcClient.sql("delete from hh_account where id = :id")
                .param("id", id)
                .update() == 1;
    }
}
