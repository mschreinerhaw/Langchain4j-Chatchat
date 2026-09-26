package com.chatchat.mcpserver.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceVendorCompatibilityConfigurationTest {

    @Test
    void replacesPostgresqlLockTimeoutWhenUrlWasSwitchedToMysql() {
        HikariDataSource dataSource = datasource(
            "jdbc:mysql://localhost:3306/live_runtime_mcp",
            "SET lock_timeout = '5s'");
        MockEnvironment environment = new MockEnvironment()
            .withProperty("CHATCHAT_MCP_DB_LOCK_WAIT_SECONDS", "7")
            .withProperty("CHATCHAT_MCP_DB_ROW_LOCK_WAIT_SECONDS", "9");

        DatasourceVendorCompatibilityConfiguration.alignConnectionInitSql(
            dataSource, "dataSource", environment);

        assertThat(dataSource.getConnectionInitSql())
            .isEqualTo("SET SESSION lock_wait_timeout=7, SESSION innodb_lock_wait_timeout=9");
    }

    @Test
    void replacesMysqlLockTimeoutWhenUrlWasSwitchedToPostgresql() {
        HikariDataSource dataSource = datasource(
            "jdbc:postgresql://localhost:5432/live_runtime_mcp",
            "SET SESSION lock_wait_timeout=5, SESSION innodb_lock_wait_timeout=5");

        DatasourceVendorCompatibilityConfiguration.alignConnectionInitSql(
            dataSource, "dataSource", new MockEnvironment());

        assertThat(dataSource.getConnectionInitSql()).isEqualTo("SET lock_timeout = '5s'");
    }

    @Test
    void removesKnownVendorSqlWhenUrlWasSwitchedToH2() {
        HikariDataSource dataSource = datasource(
            "jdbc:h2:file:./data/mcp",
            "SET lock_timeout = '5s'");

        DatasourceVendorCompatibilityConfiguration.alignConnectionInitSql(
            dataSource, "dataSource", new MockEnvironment());

        assertThat(dataSource.getConnectionInitSql()).isNull();
    }

    @Test
    void preservesCustomInitializationSql() {
        HikariDataSource dataSource = datasource(
            "jdbc:mysql://localhost:3306/live_runtime_mcp",
            "SET SESSION sql_mode='STRICT_ALL_TABLES'");

        DatasourceVendorCompatibilityConfiguration.alignConnectionInitSql(
            dataSource, "dataSource", new MockEnvironment());

        assertThat(dataSource.getConnectionInitSql())
            .isEqualTo("SET SESSION sql_mode='STRICT_ALL_TABLES'");
    }

    private HikariDataSource datasource(String jdbcUrl, String initSql) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setConnectionInitSql(initSql);
        return dataSource;
    }
}
