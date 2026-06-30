package com.chatchat.mcpserver.sql;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SqlDatasourceSchemaMigratorTest {

    @Test
    void addsMetadataScopeColumnsToExistingDatasourceTable() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        jdbcTemplate.execute("""
            CREATE TABLE mcp_sql_datasource (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(200) NOT NULL,
                jdbc_url VARCHAR(2000) NOT NULL,
                enabled BOOLEAN NOT NULL
            )
            """);
        jdbcTemplate.update("""
            INSERT INTO mcp_sql_datasource (id, name, jdbc_url, enabled)
            VALUES ('ds-1', 'test', 'jdbc:h2:mem:test', TRUE)
            """);

        new SqlDatasourceSchemaMigrator(jdbcTemplate).run(null);

        assertThat(columnExists(jdbcTemplate, "metadata_scope_type")).isTrue();
        assertThat(columnExists(jdbcTemplate, "metadata_scope_value")).isTrue();
        assertThat(columnExists(jdbcTemplate, "metadata_auto_refresh_enabled")).isTrue();
        assertThat(columnExists(jdbcTemplate, "metadata_refresh_interval_minutes")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT metadata_scope_type FROM mcp_sql_datasource WHERE id = 'ds-1'",
            String.class
        )).isEqualTo("JDBC_DATABASE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT metadata_auto_refresh_enabled FROM mcp_sql_datasource WHERE id = 'ds-1'",
            Boolean.class
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT metadata_refresh_interval_minutes FROM mcp_sql_datasource WHERE id = 'ds-1'",
            Integer.class
        )).isEqualTo(60);
    }

    private boolean columnExists(JdbcTemplate jdbcTemplate, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_name = 'mcp_sql_datasource'
              AND column_name = ?
            """, Integer.class, columnName);
        return count != null && count > 0;
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:sql_datasource_schema_migrator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
