package com.chatchat.mcpserver.external;

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

/** Creates the external MCP registry table for existing production databases. */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExternalMcpSchemaMigrator implements ApplicationRunner {

    static final String TABLE_NAME = "mcp_external_service";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            migrate(connection);
            return null;
        });
    }

    void migrate(Connection connection) throws SQLException {
        if (tableExists(connection)) {
            return;
        }
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        String largeText = product.contains("mysql") || product.contains("mariadb") ? "LONGTEXT" : "TEXT";
        String timestamp = product.contains("mysql") || product.contains("mariadb")
            ? "DATETIME(6)" : "TIMESTAMP(6) WITH TIME ZONE";
        String sql = """
            CREATE TABLE IF NOT EXISTS mcp_external_service (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                name VARCHAR(200) NOT NULL,
                endpoint VARCHAR(2000) NOT NULL,
                authorization_header VARCHAR(2000),
                parent_tool_name VARCHAR(128) NOT NULL,
                workflow_id VARCHAR(64) NOT NULL,
                enabled BOOLEAN NOT NULL,
                templates_json %s,
                discovered_at %s,
                created_at %s NOT NULL,
                updated_at %s NOT NULL
            )
            """.formatted(largeText, timestamp, timestamp, timestamp);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        log.info("External MCP schema migrated: created table {}", TABLE_NAME);
    }

    private boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(TABLE_NAME, TABLE_NAME.toUpperCase(Locale.ROOT))) {
            try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, candidate, new String[] {"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}
