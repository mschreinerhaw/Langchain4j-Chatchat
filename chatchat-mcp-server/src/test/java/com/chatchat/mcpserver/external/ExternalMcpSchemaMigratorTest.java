package com.chatchat.mcpserver.external;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalMcpSchemaMigratorTest {

    @ParameterizedTest
    @ValueSource(strings = {"PostgreSQL", "MySQL"})
    void createsExternalMcpTableForExistingDatabaseAndIsIdempotent(String mode) {
        JdbcTemplate jdbc = jdbc(mode);
        ExternalMcpSchemaMigrator migrator = new ExternalMcpSchemaMigrator(jdbc);

        migrator.run(null);
        migrator.run(null);

        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO mcp_external_service (
                id, name, endpoint, authorization_header, parent_tool_name, workflow_id, enabled,
                templates_json, discovered_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, "external-1", "Partner MCP", "https://partner.example/mcp", null,
            "api_template_query", "mcp_streamable_http", false, null, null, now, now);

        assertThat(jdbc.queryForObject(
            "SELECT name FROM mcp_external_service WHERE id = ?", String.class, "external-1"))
            .isEqualTo("Partner MCP");
    }

    private JdbcTemplate jdbc(String mode) {
        String name = "external_mcp_schema_" + mode.toLowerCase(Locale.ROOT);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:" + name + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1", "sa", "");
        return new JdbcTemplate(dataSource);
    }
}
