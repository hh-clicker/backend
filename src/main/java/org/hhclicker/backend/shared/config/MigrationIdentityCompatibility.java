package org.hhclicker.backend.shared.config;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.Customizer;
import liquibase.structure.core.Table;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves two baseline identities without changing the installed migration history. */
final class MigrationIdentityCompatibility implements Customizer<Liquibase> {

    static final String DEFAULT_AUTHOR = "hh-clicker";
    static final String SCHEMA_AUTHOR = "hhclicker.bootstrap.schemaAuthor";
    static final String NOTIFICATIONS_AUTHOR = "hhclicker.bootstrap.notificationsAuthor";
    private static final String CHANGESET_DIRECTORY = "db/changelog/changes/";
    private static final String SCHEMA_ID = "001-create-schema";
    private static final String NOTIFICATIONS_ID = "019-relevance-notifications";
    private static final Set<String> EXECUTED = Set.of("EXECUTED", "RERAN");

    @Override
    public void customize(Liquibase liquibase) {
        Database database = liquibase.getDatabase();
        if (!(database.getConnection() instanceof JdbcConnection connection)
                || !"postgresql".equals(database.getShortName())) {
            throw incompatible("Для проверки истории миграций требуется JDBC-подключение PostgreSQL.");
        }
        try {
            // The connection belongs to SpringLiquibase. Close statements, never the connection.
            Connection jdbc = connection.getUnderlyingConnection();
            String historyTable = database.escapeTableName(database.getLiquibaseCatalogName(),
                    database.getLiquibaseSchemaName(),
                    database.correctObjectName(database.getDatabaseChangeLogTableName(), Table.class));
            List<HistoryEntry> history = relationExists(jdbc, historyTable)
                    ? readHistory(jdbc, historyTable) : List.of();
            if (history.isEmpty() && hasApplicationTables(jdbc, database)) {
                throw incompatible("В схеме есть таблицы приложения, но отсутствует история миграций.");
            }
            Map<String, String> authors = resolve(history);
            for (String parameter : authors.keySet()) {
                if (liquibase.getChangeLogParameters().getValue(parameter, null) != null) {
                    throw incompatible("Зарезервированные параметры авторов миграций не должны задаваться извне.");
                }
            }
            authors.forEach(liquibase::setChangeLogParameter);
        } catch (SQLException exception) {
            throw new IllegalStateException("Не удалось прочитать историю миграций перед запуском Liquibase.", exception);
        }
    }

    static Map<String, String> resolve(List<HistoryEntry> history) {
        if (history.isEmpty()) return authors(DEFAULT_AUTHOR, DEFAULT_AUTHOR);
        HistoryEntry schema = find(history, SCHEMA_ID);
        HistoryEntry notifications = find(history, NOTIFICATIONS_ID);
        if (schema == null) {
            throw incompatible("В непустой истории отсутствует начальная миграция схемы.");
        }
        if (notifications == null) {
            validateNeutralPrefix(history);
            return authors(DEFAULT_AUTHOR, DEFAULT_AUTHOR);
        }
        return authors(schema.author(), notifications.author());
    }

    private static HistoryEntry find(List<HistoryEntry> history, String id) {
        List<HistoryEntry> matches = history.stream().filter(entry -> id.equals(entry.id())).toList();
        if (matches.isEmpty()) return null;
        if (matches.size() != 1) throw incompatible("Базовая миграция имеет неоднозначную историю.");
        HistoryEntry entry = matches.getFirst();
        if (!(CHANGESET_DIRECTORY + id + ".yaml").equals(entry.filename())
                || entry.author() == null || entry.author().isBlank()
                || !executed(entry)) {
            throw incompatible("Некорректная запись базовой миграции.");
        }
        return entry;
    }

    private static void validateNeutralPrefix(List<HistoryEntry> history) {
        // A fresh installation may stop between changesets 001 and 018. It must be resumable.
        Set<Integer> positions = new HashSet<>();
        for (HistoryEntry entry : history) {
            if (!DEFAULT_AUTHOR.equals(entry.author()) || !executed(entry)
                    || entry.id() == null || !entry.id().matches("(?:00[1-9]|01[0-8])-[a-z0-9-]+")
                    || !(CHANGESET_DIRECTORY + entry.id() + ".yaml").equals(entry.filename())
                    || !positions.add(Integer.parseInt(entry.id().substring(0, 3)))) {
                throw incompatible("Отсутствует ожидаемая базовая миграция. Сначала завершите запуск версии 1.12.0.");
            }
        }
        for (int position = 1; position <= history.size(); position++) {
            if (!positions.contains(position)) throw incompatible("История начальных миграций содержит пропуски.");
        }
    }

    private static Map<String, String> authors(String schema, String notifications) {
        return Map.of(SCHEMA_AUTHOR, schema, NOTIFICATIONS_AUTHOR, notifications);
    }

    private static boolean executed(HistoryEntry entry) {
        return entry.executionType() != null && EXECUTED.contains(entry.executionType());
    }

    private static boolean relationExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static List<HistoryEntry> readHistory(Connection connection, String table) throws SQLException {
        List<HistoryEntry> history = new ArrayList<>();
        // The identifier is escaped by Liquibase using its configured schema and table name.
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, author, filename, exectype from " + table);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                history.add(new HistoryEntry(rows.getString("id"), rows.getString("author"),
                        rows.getString("filename"), rows.getString("exectype")));
            }
        }
        return history;
    }

    private static boolean hasApplicationTables(Connection connection, Database database) throws SQLException {
        String schema = database.getDefaultSchemaName();
        if (schema == null || schema.isBlank()) schema = connection.getSchema();
        String historySchema = database.getLiquibaseSchemaName();
        if (historySchema == null || historySchema.isBlank()) historySchema = schema;
        try (PreparedStatement statement = connection.prepareStatement("""
                select exists (
                    select 1 from pg_catalog.pg_class c
                    join pg_catalog.pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and c.relkind in ('r', 'p')
                      and not (n.nspname = ? and c.relname in (?, ?))
                )
                """)) {
            statement.setString(1, schema);
            statement.setString(2, historySchema);
            statement.setString(3, database.correctObjectName(database.getDatabaseChangeLogTableName(), Table.class));
            statement.setString(4, database.correctObjectName(database.getDatabaseChangeLogLockTableName(), Table.class));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static IllegalStateException incompatible(String message) {
        return new IllegalStateException("Несовместимая история миграций HH Clicker. " + message);
    }

    record HistoryEntry(String id, String author, String filename, String executionType) { }
}
