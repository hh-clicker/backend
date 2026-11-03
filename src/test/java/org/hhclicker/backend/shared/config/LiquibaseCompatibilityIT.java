package org.hhclicker.backend.shared.config;

import liquibase.exception.ValidationFailedException;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.hhclicker.backend.support.ApplicationDatabaseFixtures;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hhclicker.backend.shared.config.MigrationIdentityCompatibility.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses isolated PostgreSQL 17 databases; no application workers or external agents are started. */
class LiquibaseCompatibilityIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("compatibility_admin")
            .withUsername("hh_clicker")
            .withPassword("synthetic-test-password");
    private static final String BASELINE = "classpath:db/compatibility/baseline-1.12.0.yaml";
    private static final String MASTER = "classpath:db/changelog/db.changelog-master.yaml";
    private static final int BASELINE_CHANGESET_COUNT = 35;
    private static final List<String> RELEASE_ADDITIONS = List.of("036-market-analysis-batches");
    private static final String PREVIOUS_SCHEMA_AUTHOR = "previous-schema-maintainer";
    private static final String PREVIOUS_NOTIFICATIONS_AUTHOR = "previous-notifications-maintainer";
    private static final HistoryLocation DEFAULT_HISTORY = new HistoryLocation(
            "public", "databasechangelog", "databasechangeloglock");
    private String jdbcUrl;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + name);
        }
        jdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    @Test
    void cleanInstallationUsesNeutralAuthorsAndRepeatedStartupDoesNotRepeatMigrations() {
        runner(DEFAULT_HISTORY).run(context -> assertNull(context.getStartupFailure()));
        List<Map<String, Object>> firstHistory = history(DEFAULT_HISTORY);

        assertEquals(BASELINE_CHANGESET_COUNT + RELEASE_ADDITIONS.size(), firstHistory.size());
        assertTrue(firstHistory.stream().allMatch(row -> DEFAULT_AUTHOR.equals(row.get("author"))));
        runner(DEFAULT_HISTORY).run(context -> assertNull(context.getStartupFailure()));
        assertEquals(firstHistory, history(DEFAULT_HISTORY));
    }

    @Test
    void baselineUpgradePreservesAllApplicationRowsAndMigrationRecords() throws Exception {
        seedPreviousBaseline(DEFAULT_HISTORY);
        var fixtures = new ApplicationDatabaseFixtures(JdbcClient.create(dataSource()));
        var workspace = fixtures.workspace();
        fixtures.queuedVacancy(workspace, "700001", 0);
        jdbc().update("update hhclicker.notification_settings set sound_enabled = false");
        List<Map<String, Object>> beforeHistory = history(DEFAULT_HISTORY);
        Map<String, List<String>> beforeData = applicationData();

        runner(DEFAULT_HISTORY).run(context -> assertNull(context.getStartupFailure()));
        List<Map<String, Object>> upgradedHistory = history(DEFAULT_HISTORY);
        runner(DEFAULT_HISTORY).run(context -> assertNull(context.getStartupFailure()));

        assertUpgradedHistory(beforeHistory, upgradedHistory, RELEASE_ADDITIONS);
        assertEquals(upgradedHistory, history(DEFAULT_HISTORY));
        Map<String, List<String>> afterData = new LinkedHashMap<>(applicationData());
        beforeData.forEach((table, rows) -> assertEquals(rows, afterData.remove(table), table));
        assertEquals(Map.of("market_analysis_batch", List.<String>of()), afterData);
    }

    @Test
    void previousBaselineCanUpgradeDirectlyToAReleaseWithAdditionalChanges() throws Exception {
        seedPreviousBaseline(DEFAULT_HISTORY);
        List<Map<String, Object>> beforeHistory = history(DEFAULT_HISTORY);

        var future = runner(DEFAULT_HISTORY).withPropertyValues(
                "spring.liquibase.change-log=classpath:db/compatibility/future-release.yaml");
        future.run(context -> assertNull(context.getStartupFailure()));
        List<Map<String, Object>> upgradedHistory = history(DEFAULT_HISTORY);
        future.run(context -> assertNull(context.getStartupFailure()));

        List<Map<String, Object>> after = history(DEFAULT_HISTORY);
        var additions = new java.util.ArrayList<>(RELEASE_ADDITIONS);
        additions.add("900-upgrade-compatibility-probe");
        assertUpgradedHistory(beforeHistory, after, additions);
        assertEquals(upgradedHistory, after);
        assertEquals(1, jdbc().queryForObject("""
                select count(*) from public.databasechangelog where id = '900-upgrade-compatibility-probe'
                """, Integer.class));
        assertNotNull(jdbc().queryForObject(
                "select to_regclass('hhclicker.upgrade_compatibility_probe')::text", String.class));
    }

    @Test
    void interruptedNeutralInstallationResumesRatherThanBeingTreatedAsLegacyCorruption() throws Exception {
        seed("classpath:db/compatibility/initial-schema-only.yaml", DEFAULT_HISTORY,
                DEFAULT_AUTHOR, DEFAULT_AUTHOR);
        assertEquals(1, history(DEFAULT_HISTORY).size());

        runner(DEFAULT_HISTORY).run(context -> assertNull(context.getStartupFailure()));

        assertEquals(BASELINE_CHANGESET_COUNT + RELEASE_ADDITIONS.size(), history(DEFAULT_HISTORY).size());
        assertTrue(history(DEFAULT_HISTORY).stream().allMatch(row -> DEFAULT_AUTHOR.equals(row.get("author"))));
    }

    @Test
    void configuredHistorySchemaAndTableAreRespected() throws Exception {
        HistoryLocation custom = new HistoryLocation("migration_history", "compat_history", "compat_lock");
        jdbc().execute("create schema migration_history");
        seedPreviousBaseline(custom);
        List<Map<String, Object>> before = history(custom);

        runner(custom).run(context -> assertNull(context.getStartupFailure()));

        assertUpgradedHistory(before, history(custom), RELEASE_ADDITIONS);
        assertNull(jdbc().queryForObject("select to_regclass('public.databasechangelog')::text", String.class));
    }

    @Test
    void checksumValidationRemainsEnabled() throws Exception {
        seedPreviousBaseline(DEFAULT_HISTORY);
        jdbc().update("""
                update public.databasechangelog set md5sum = '9:00000000000000000000000000000000'
                where id = '019-relevance-notifications'
                """);
        List<Map<String, Object>> before = history(DEFAULT_HISTORY);

        runner(DEFAULT_HISTORY).run(context ->
                assertTrue(hasCause(context.getStartupFailure(), ValidationFailedException.class)));

        assertEquals(before, history(DEFAULT_HISTORY));
    }

    @Test
    void duplicateBaselineRecordsStopStartupWithoutChangingData() throws Exception {
        seedPreviousBaseline(DEFAULT_HISTORY);
        jdbc().update("""
                insert into public.databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype)
                select id, 'another-test-maintainer', filename, dateexecuted, orderexecuted, exectype
                from public.databasechangelog where id = '019-relevance-notifications'
                """);
        assertIncompatibleHistoryIsUnchanged();
    }

    @Test
    void missingBaselineInAnOtherwiseCompleteHistoryStopsStartup() throws Exception {
        seedPreviousBaseline(DEFAULT_HISTORY);
        jdbc().update("delete from public.databasechangelog where id = '019-relevance-notifications'");

        assertIncompatibleHistoryIsUnchanged();
    }

    @Test
    void existingApplicationTablesWithoutHistoryAreNotMistakenForANewDatabase() {
        jdbc().execute("create schema hhclicker");
        jdbc().execute("create table hhclicker.orphaned_data (id integer)");
        jdbc().update("insert into hhclicker.orphaned_data (id) values (1)");

        runner(DEFAULT_HISTORY).run(context -> assertTrue(hasCompatibilityFailure(context.getStartupFailure())));

        assertEquals(1, jdbc().queryForObject("select id from hhclicker.orphaned_data", Integer.class));
        assertNull(jdbc().queryForObject("select to_regclass('public.databasechangelog')::text", String.class));
    }

    @Test
    void reservedAuthorParametersCannotOverrideTheDetectedHistory() throws Exception {
        seedPreviousBaseline(DEFAULT_HISTORY);
        List<Map<String, Object>> before = history(DEFAULT_HISTORY);

        runner(DEFAULT_HISTORY).withPropertyValues(
                "spring.liquibase.parameters[hhclicker.bootstrap.schemaAuthor]=unexpected-author")
                .run(context -> assertTrue(hasCompatibilityFailure(context.getStartupFailure())));

        assertEquals(before, history(DEFAULT_HISTORY));
    }

    @Test
    void concurrentStartupsAgainstAnInstalledBaselineDoNotDuplicateMigrations() throws Exception {
        seedPreviousBaseline(DEFAULT_HISTORY);
        List<Map<String, Object>> before = history(DEFAULT_HISTORY);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runner(DEFAULT_HISTORY)
                    .run(context -> assertNull(context.getStartupFailure())));
            var second = executor.submit(() -> runner(DEFAULT_HISTORY)
                    .run(context -> assertNull(context.getStartupFailure())));
            first.get(90, TimeUnit.SECONDS);
            second.get(90, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertUpgradedHistory(before, history(DEFAULT_HISTORY), RELEASE_ADDITIONS);
    }

    private void assertUpgradedHistory(List<Map<String, Object>> before, List<Map<String, Object>> after,
                                       List<String> additions) {
        assertEquals(BASELINE_CHANGESET_COUNT, before.size());
        assertEquals(before.size() + additions.size(), after.size());
        // Preserve every legacy field, including authors, checksums and execution timestamps.
        assertEquals(before, after.subList(0, before.size()));
        var appended = after.subList(before.size(), after.size());
        assertEquals(additions, appended.stream().map(row -> row.get("id")).toList());
        assertTrue(appended.stream().allMatch(row -> DEFAULT_AUTHOR.equals(row.get("author"))
                && "EXECUTED".equals(row.get("exectype"))));
    }

    private void assertIncompatibleHistoryIsUnchanged() {
        List<Map<String, Object>> beforeHistory = history(DEFAULT_HISTORY);
        Map<String, List<String>> beforeData = applicationData();

        runner(DEFAULT_HISTORY).run(context -> assertTrue(hasCompatibilityFailure(context.getStartupFailure())));

        assertEquals(beforeHistory, history(DEFAULT_HISTORY));
        assertEquals(beforeData, applicationData());
    }

    private void seedPreviousBaseline(HistoryLocation location) throws Exception {
        seed(BASELINE, location, PREVIOUS_SCHEMA_AUTHOR, PREVIOUS_NOTIFICATIONS_AUTHOR);
    }

    private void seed(String changelog, HistoryLocation location, String schemaAuthor,
                      String notificationsAuthor) throws Exception {
        // Synthetic authors reproduce the old identity semantics without publishing personal values.
        SpringLiquibase seed = new SpringLiquibase();
        seed.setDataSource(dataSource());
        seed.setResourceLoader(new DefaultResourceLoader());
        seed.setChangeLog(changelog);
        seed.setDefaultSchema("hhclicker");
        seed.setLiquibaseSchema(location.schema());
        seed.setDatabaseChangeLogTable(location.table());
        seed.setDatabaseChangeLogLockTable(location.lockTable());
        seed.setAnalyticsEnabled(false);
        seed.setChangeLogParameters(Map.of(SCHEMA_AUTHOR, schemaAuthor, NOTIFICATIONS_AUTHOR, notificationsAuthor));
        seed.afterPropertiesSet();
    }

    private ApplicationContextRunner runner(HistoryLocation location) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        LiquibaseAutoConfiguration.class))
                .withUserConfiguration(LiquibaseCompatibilityConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.datasource.hikari.schema=hhclicker",
                        "spring.liquibase.change-log=" + MASTER,
                        "spring.liquibase.default-schema=hhclicker",
                        "spring.liquibase.liquibase-schema=" + location.schema(),
                        "spring.liquibase.database-change-log-table=" + location.table(),
                        "spring.liquibase.database-change-log-lock-table=" + location.lockTable(),
                        "spring.liquibase.analytics-enabled=false");
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl + "?currentSchema=hhclicker",
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private List<Map<String, Object>> history(HistoryLocation location) {
        return jdbc().queryForList("select * from " + location.schema() + "." + location.table()
                + " order by orderexecuted, id, author");
    }

    private Map<String, List<String>> applicationData() {
        List<String> tables = jdbc().queryForList("""
                select tablename from pg_catalog.pg_tables where schemaname = 'hhclicker' order by tablename
                """, String.class);
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            String quoted = '"' + table.replace("\"", "\"\"") + '"';
            snapshot.put(table, jdbc().queryForList(
                    "select to_jsonb(t)::text from hhclicker." + quoted + " t order by 1", String.class));
        }
        return snapshot;
    }

    private static boolean hasCompatibilityFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().startsWith("Несовместимая история миграций HH Clicker.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private record HistoryLocation(String schema, String table, String lockTable) { }
}
