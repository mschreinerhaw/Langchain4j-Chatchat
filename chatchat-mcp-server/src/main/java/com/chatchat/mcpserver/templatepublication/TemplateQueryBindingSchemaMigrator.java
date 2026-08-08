package com.chatchat.mcpserver.templatepublication;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Upgrades the original service/role-only publication uniqueness rule to the
 * current domain and subject aware rule. Hibernate update can add a new unique
 * key, but deliberately does not remove an obsolete one from an existing DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TemplateQueryBindingSchemaMigrator implements ApplicationRunner {

    static final String TABLE_NAME = "mcp_template_query_binding";
    static final String CURRENT_UNIQUE_KEY = "uk_template_query_service_role_domain_subject";
    private static final List<String> LEGACY_COLUMNS = List.of("service_id", "role_id");
    private static final List<String> CURRENT_COLUMNS = List.of(
        "service_id", "role_id", "domain_code", "subject_type", "subject_id");

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            migrate(connection);
            return null;
        });
    }

    void migrate(Connection connection) throws SQLException {
        if (!tableExists(connection)) {
            return;
        }
        Map<String, List<String>> uniqueIndexes = uniqueIndexes(connection);
        for (Map.Entry<String, List<String>> index : uniqueIndexes.entrySet()) {
            if (LEGACY_COLUMNS.equals(index.getValue())) {
                dropUniqueKey(connection, index.getKey());
                log.info("Template query binding schema migrated: removed obsolete unique key {}", index.getKey());
            }
        }
        if (uniqueIndexes(connection).values().stream().noneMatch(CURRENT_COLUMNS::equals)) {
            execute(connection, "ALTER TABLE " + TABLE_NAME + " ADD CONSTRAINT " + CURRENT_UNIQUE_KEY
                + " UNIQUE (service_id, role_id, domain_code, subject_type, subject_id)");
            log.info("Template query binding schema migrated: added unique key {}", CURRENT_UNIQUE_KEY);
        }
    }

    private Map<String, List<String>> uniqueIndexes(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, List<IndexedColumn>> indexedColumns = new LinkedHashMap<>();
        for (String table : identifierCandidates(TABLE_NAME)) {
            try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), null, table, true, false)) {
                while (indexes.next()) {
                    String indexName = indexes.getString("INDEX_NAME");
                    String columnName = indexes.getString("COLUMN_NAME");
                    if (indexName == null || columnName == null || "primary".equalsIgnoreCase(indexName)) {
                        continue;
                    }
                    indexedColumns.computeIfAbsent(indexName, ignored -> new ArrayList<>())
                        .add(new IndexedColumn(indexes.getShort("ORDINAL_POSITION"),
                            columnName.toLowerCase(Locale.ROOT)));
                }
            }
            if (!indexedColumns.isEmpty()) {
                break;
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        indexedColumns.forEach((name, columns) -> result.put(name, columns.stream()
            .sorted(java.util.Comparator.comparingInt(IndexedColumn::position))
            .map(IndexedColumn::name)
            .toList()));
        return result;
    }

    private boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String table : identifierCandidates(TABLE_NAME)) {
            try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, table, new String[] {"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dropUniqueKey(Connection connection, String indexName) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("mysql") || product.contains("mariadb")) {
            execute(connection, "ALTER TABLE " + TABLE_NAME + " DROP INDEX " + indexName);
            return;
        }
        String constraintName = product.contains("h2") ? h2LegacyConstraintName(connection) : indexName;
        execute(connection, "ALTER TABLE " + TABLE_NAME + " DROP CONSTRAINT " + constraintName);
    }

    private String h2LegacyConstraintName(Connection connection) throws SQLException {
        String sql = """
            SELECT tc.CONSTRAINT_NAME, kcu.COLUMN_NAME, kcu.ORDINAL_POSITION
              FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
              JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                ON tc.CONSTRAINT_CATALOG = kcu.CONSTRAINT_CATALOG
               AND tc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
               AND tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
             WHERE UPPER(tc.TABLE_NAME) = UPPER(?)
               AND tc.CONSTRAINT_TYPE = 'UNIQUE'
             ORDER BY tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION
            """;
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TABLE_NAME);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.computeIfAbsent(rows.getString("CONSTRAINT_NAME"), ignored -> new ArrayList<>())
                        .add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return constraints.entrySet().stream()
            .filter(entry -> LEGACY_COLUMNS.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new SQLException("Legacy template query unique constraint was not found"));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> identifierCandidates(String identifier) {
        return List.of(identifier, identifier.toUpperCase(Locale.ROOT), identifier.toLowerCase(Locale.ROOT));
    }

    private record IndexedColumn(int position, String name) {
    }
}
