package org.hhclicker.backend.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in acceptance check against a restored, marked database, never a live installation. */
@EnabledIfEnvironmentVariable(named = "HH_CLICKER_UPGRADE_JDBC_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hhclicker_upgrade_restore")
class RestoredDatabaseCompatibilityIT {

    @Test
    void restoredBaselinePreservesExistingDataAndAppliesNewMigrationsOnce() {
        String url = System.getenv("HH_CLICKER_UPGRADE_JDBC_URL");
        String user = System.getenv("HH_CLICKER_UPGRADE_DB_USER");
        String password = System.getenv("HH_CLICKER_UPGRADE_DB_PASSWORD");
        String verificationId = System.getenv("HH_CLICKER_UPGRADE_VERIFICATION_ID");
        assertNotNull(verificationId);
        assertTrue(verificationId.matches("[0-9a-f]{12}"));
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertEquals("hhclicker_upgrade_restore", jdbc.queryForObject("select current_database()", String.class));
        assertEquals(List.of(verificationId), jdbc.queryForList(
                "select id from public.hh_clicker_upgrade_verification", String.class));

        Map<String, Fingerprint> before = fingerprints(jdbc);
        assertEquals(35, before.get("public.databasechangelog").rows());
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        LiquibaseAutoConfiguration.class))
                .withUserConfiguration(LiquibaseCompatibilityConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=" + user,
                        "spring.datasource.password=" + password,
                        "spring.datasource.hikari.schema=hhclicker",
                        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml",
                        "spring.liquibase.default-schema=hhclicker",
                        "spring.liquibase.liquibase-schema=public",
                        "spring.liquibase.analytics-enabled=false");

        // Only DataSource and Liquibase are initialized: no backend workers, HTTP server or agents.
        runner.run(context -> assertNull(context.getStartupFailure()));
        Map<String, Fingerprint> upgraded = fingerprints(jdbc);
        assertEquals(36, upgraded.get("public.databasechangelog").rows());
        assertEquals(before.get("public.databasechangelog"), fingerprint(jdbc, "public.databasechangelog",
                "where id <> '036-market-analysis-batches'"));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from public.databasechangelog
                where id = '036-market-analysis-batches' and author = 'hh-clicker'
                  and filename = 'db/changelog/changes/036-market-analysis-batches.yaml' and exectype = 'EXECUTED'
                """, Integer.class));
        Map<String, Fingerprint> existingBefore = new LinkedHashMap<>(before);
        Map<String, Fingerprint> existingAfter = new LinkedHashMap<>(upgraded);
        existingBefore.remove("public.databasechangelog");
        existingAfter.remove("public.databasechangelog");
        Fingerprint newBatches = existingAfter.remove("hhclicker.market_analysis_batch");
        assertNotNull(newBatches);
        assertEquals(0, newBatches.rows());
        assertEquals(existingBefore, existingAfter);
        runner.run(context -> assertNull(context.getStartupFailure()));
        assertEquals(upgraded, fingerprints(jdbc));
    }

    private static Map<String, Fingerprint> fingerprints(JdbcTemplate jdbc) {
        Map<String, Fingerprint> result = new LinkedHashMap<>();
        result.put("public.databasechangelog", fingerprint(jdbc, "public.databasechangelog"));
        for (String table : jdbc.queryForList("""
                select tablename from pg_catalog.pg_tables where schemaname = 'hhclicker' order by tablename
                """, String.class)) {
            String quoted = "hhclicker.\"" + table.replace("\"", "\"\"") + "\"";
            result.put("hhclicker." + table, fingerprint(jdbc, quoted));
        }
        return result;
    }

    private static Fingerprint fingerprint(JdbcTemplate jdbc, String table) {
        return fingerprint(jdbc, table, "");
    }

    private static Fingerprint fingerprint(JdbcTemplate jdbc, String table, String filter) {
        // Hash inside PostgreSQL so assertion reports never contain resume texts or credentials.
        return jdbc.queryForObject("""
                select count(*) as row_count,
                       md5(coalesce(string_agg(row_data, chr(10) order by row_data), '')) as digest
                from (select to_jsonb(t)::text as row_data from %s t %s) snapshot
                """.formatted(table, filter), (row, index) -> new Fingerprint(row.getLong("row_count"), row.getString("digest")));
    }

    private record Fingerprint(long rows, String digest) { }
}
