package com.chatchat.chat.skills.federation;

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
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpSkillFederationSchemaMigrator implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            migrate(connection);
            return null;
        });
    }

    void migrate(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        boolean mysql = product.contains("mysql") || product.contains("mariadb");
        String timestamp = mysql ? "DATETIME(6)" : "TIMESTAMP(6) WITH TIME ZONE";
        String largeText = mysql ? "LONGTEXT" : "TEXT";
        if (!tableExists(connection, "ds_mcp_skill_source")) {
            execute(connection, """
                CREATE TABLE ds_mcp_skill_source (
                    allow_private_network BOOLEAN NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    last_discovered_count INTEGER NOT NULL,
                    last_synced_at %s,
                    created_at %s NOT NULL,
                    updated_at %s NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    id VARCHAR(64) NOT NULL PRIMARY KEY,
                    owner_id VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    default_category VARCHAR(120) NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    authorization_header VARCHAR(2000),
                    endpoint VARCHAR(2000) NOT NULL,
                    last_error VARCHAR(2000)
                )
                """.formatted(timestamp, timestamp, timestamp));
            log.info("MCP Skill federation schema migrated: created ds_mcp_skill_source");
        }
        addColumn(connection, "ds_domain_skill", "federated_synced_at", timestamp);
        addColumn(connection, "ds_domain_skill", "federated_source_id", "VARCHAR(64)");
        addColumn(connection, "ds_domain_skill", "federated_digest", "VARCHAR(80)");
        addColumn(connection, "ds_domain_skill", "federated_source_name", "VARCHAR(200)");
        addColumn(connection, "ds_domain_skill", "federated_skill_uri", "VARCHAR(2000)");
        addColumn(connection, "ds_domain_skill", "federated_manifest_json", largeText);
        addIndex(connection, "ds_domain_skill", "idx_domain_skill_federated_source",
            "tenant_id, federated_source_id");
        addIndex(connection, "ds_mcp_skill_source", "idx_mcp_skill_source_tenant",
            "tenant_id, updated_at");
    }

    private void addIndex(Connection connection, String table, String index, String columns) throws SQLException {
        if (!indexExists(connection, table, index)) execute(connection,
            "CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
    }

    private void addColumn(Connection connection, String table, String column, String type) throws SQLException {
        if (!columnExists(connection, table, column)) execute(connection,
            "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : new String[] {table, table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet result = metadata.getTables(connection.getCatalog(), null, candidate, new String[] {"TABLE"})) {
                if (result.next()) return true;
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String tableName : new String[] {table, table.toUpperCase(Locale.ROOT)}) {
            for (String columnName : new String[] {column, column.toUpperCase(Locale.ROOT)}) {
                try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, tableName, columnName)) {
                    if (result.next()) return true;
                }
            }
        }
        return false;
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String tableName : new String[] {table, table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
                while (result.next()) {
                    String existing = result.getString("INDEX_NAME");
                    if (existing != null && index.equalsIgnoreCase(existing)) return true;
                }
            }
        }
        return false;
    }
}
