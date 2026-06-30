package com.chatchat.mcpserver.sql;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SqlDatasourceSchemaMigrator implements ApplicationRunner {

    private static final String TABLE_NAME = "mcp_sql_datasource";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            migrate(connection);
            return null;
        });
    }

    private void migrate(Connection connection) throws SQLException {
        if (!tableExists(connection, TABLE_NAME)) {
            return;
        }
        String productName = connection.getMetaData().getDatabaseProductName();
        for (ColumnDefinition column : columns(productName)) {
            ensureColumn(connection, column);
        }
        normalizeColumnDefaults(connection, productName);
    }

    private void ensureColumn(Connection connection, ColumnDefinition column) throws SQLException {
        if (columnExists(connection, TABLE_NAME, column.name())) {
            return;
        }
        String sql = "ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + column.name() + " " + column.definition();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("MCP SQL datasource schema migrated: added column {}.{}", TABLE_NAME, column.name());
        } catch (SQLException ex) {
            if (columnExists(connection, TABLE_NAME, column.name())) {
                return;
            }
            throw ex;
        }
    }

    private List<ColumnDefinition> columns(String productName) {
        String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("oracle")) {
            return List.of(
                new ColumnDefinition("metadata_scope_type", "VARCHAR2(32) DEFAULT 'JDBC_DATABASE' NOT NULL"),
                new ColumnDefinition("metadata_scope_value", "VARCHAR2(200)"),
                new ColumnDefinition("metadata_auto_refresh_enabled", "NUMBER(1) DEFAULT 0 NOT NULL"),
                new ColumnDefinition("metadata_refresh_interval_minutes", "NUMBER(10) DEFAULT 60 NOT NULL")
            );
        }
        if (normalized.contains("sql server")) {
            return List.of(
                new ColumnDefinition("metadata_scope_type", "VARCHAR(32) DEFAULT 'JDBC_DATABASE' NOT NULL"),
                new ColumnDefinition("metadata_scope_value", "VARCHAR(200)"),
                new ColumnDefinition("metadata_auto_refresh_enabled", "BIT DEFAULT 0 NOT NULL"),
                new ColumnDefinition("metadata_refresh_interval_minutes", "INT DEFAULT 60 NOT NULL")
            );
        }
        return List.of(
            new ColumnDefinition("metadata_scope_type", "VARCHAR(32) DEFAULT 'JDBC_DATABASE' NOT NULL"),
            new ColumnDefinition("metadata_scope_value", "VARCHAR(200)"),
            new ColumnDefinition("metadata_auto_refresh_enabled", "BOOLEAN DEFAULT FALSE NOT NULL"),
            new ColumnDefinition("metadata_refresh_interval_minutes", "INTEGER DEFAULT 60 NOT NULL")
        );
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String candidate : candidates(tableName)) {
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, candidate, new String[] {"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String tableCandidate : candidates(tableName)) {
            for (String columnCandidate : candidates(columnName)) {
                try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableCandidate, columnCandidate)) {
                    if (columns.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void normalizeColumnDefaults(Connection connection, String productName) throws SQLException {
        update(connection, "UPDATE " + TABLE_NAME
            + " SET metadata_scope_type = 'JDBC_DATABASE' WHERE metadata_scope_type IS NULL OR TRIM(metadata_scope_type) = ''");
        String falseLiteral = productName != null && productName.toLowerCase(Locale.ROOT).contains("oracle") ? "0" : "FALSE";
        update(connection, "UPDATE " + TABLE_NAME
            + " SET metadata_auto_refresh_enabled = " + falseLiteral + " WHERE metadata_auto_refresh_enabled IS NULL");
        update(connection, "UPDATE " + TABLE_NAME
            + " SET metadata_refresh_interval_minutes = 60 WHERE metadata_refresh_interval_minutes IS NULL OR metadata_refresh_interval_minutes < 5");
    }

    private void update(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private List<String> candidates(String identifier) {
        return List.of(identifier, identifier.toLowerCase(Locale.ROOT), identifier.toUpperCase(Locale.ROOT));
    }

    private record ColumnDefinition(String name, String definition) {
    }
}
