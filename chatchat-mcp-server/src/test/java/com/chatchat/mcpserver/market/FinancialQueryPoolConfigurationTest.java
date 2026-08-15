package com.chatchat.mcpserver.market;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialQueryPoolConfigurationTest {

    @Test
    void localFinancialStorageDoesNotReplaceBootControlPlaneDatasourceOrJdbcTemplate() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class))
            .withUserConfiguration(FinancialQueryPoolConfiguration.class)
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:control-plane;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "chatchat.mcp.market.query-pool.enabled=true",
                "chatchat.mcp.market.query-pool.storage=LOCAL_H2",
                "chatchat.mcp.market.query-pool.local-jdbc-url="
                    + "jdbc:h2:mem:financial-plane;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
            .run(context -> {
                assertThat(context).hasSingleBean(DataSource.class).hasSingleBean(JdbcTemplate.class);
                assertThat(context).hasBean("dataSource").hasBean("jdbcTemplate")
                    .hasBean("financialWriteStorage").doesNotHaveBean("financialWriteDataSource")
                    .doesNotHaveBean("financialWriteJdbcTemplate");
                JdbcTemplate controlPlane = context.getBean(JdbcTemplate.class);
                controlPlane.execute("create table api_service_config_probe(id integer primary key)");
                assertThat(controlPlane.queryForObject(
                    "select count(*) from api_service_config_probe", Integer.class)).isZero();
            });
    }

    @Test
    void configuresIndependentMySqlServerAndNetworkDeadlines() {
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUrl("jdbc:mysql://database.test:3306/financial");
        primary.setUsername("reader");
        primary.setPassword("secret");
        primary.setDriverClassName("com.mysql.cj.jdbc.Driver");
        FinancialQueryPoolProperties properties = new FinancialQueryPoolProperties();
        properties.setQueryTimeoutSeconds(7);
        properties.setNetworkTimeoutMs(9_000);
        properties.setServerExecutionTimeoutMs(6_500);

        HikariConfig config = new FinancialQueryPoolConfiguration().hikariConfig(primary, properties);

        assertThat(config.getConnectionInitSql()).isEqualTo("SET SESSION MAX_EXECUTION_TIME=6500");
        assertThat(config.getDataSourceProperties())
            .containsEntry("enableQueryTimeouts", true)
            .containsEntry("socketTimeout", 9_000);
    }

    @Test
    void startsDedicatedPoolAndExecutesBoundedPositionalAndNamedReads() {
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUrl("jdbc:h2:mem:isolated-financial-pool;MODE=MySQL;DB_CLOSE_DELAY=-1");
        primary.setUsername("sa");
        primary.setPassword("");
        primary.setDriverClassName("org.h2.Driver");

        FinancialQueryPoolProperties properties = new FinancialQueryPoolProperties();
        properties.setMaximumPoolSize(2);
        properties.setMinimumIdle(0);
        properties.setConnectionTimeoutMs(500);
        properties.setQueryTimeoutSeconds(2);

        FinancialQueryPoolConfiguration configuration = new FinancialQueryPoolConfiguration();
        try (FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations reads =
                 configuration.primaryFinancialReadOperations(primary, properties)) {
            assertThat(reads.queryForList("select ? as metric_value", 7))
                .singleElement().satisfies(row -> assertThat(row).containsValue(7));
            assertThat(reads.queryForList("select cast(:value as integer) as metric_value",
                    Map.of("value", 9), 1, 2))
                .singleElement().satisfies(row -> assertThat(row).containsValue(9));
        }
    }

    @Test
    void localH2SeparatesCollectorWritesFromReadOnlyOnlineQueries(@TempDir Path tempDir) {
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUrl("jdbc:mysql://control-plane.test:3306/chatchat");
        primary.setUsername("control");
        primary.setPassword("secret");
        primary.setDriverClassName("com.mysql.cj.jdbc.Driver");
        FinancialQueryPoolProperties properties = new FinancialQueryPoolProperties();
        properties.setStorage("LOCAL_H2");
        String writerPath = tempDir.resolve("writer/financial-market").toAbsolutePath()
            .toString().replace('\\', '/');
        properties.setLocalJdbcUrl("jdbc:h2:file:" + writerPath
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        properties.setSnapshotRoot(tempDir.resolve("snapshots").toString());
        properties.setMaximumPoolSize(2);
        properties.setMinimumIdle(0);

        FinancialQueryPoolConfiguration configuration = new FinancialQueryPoolConfiguration();
        try (var writer = configuration.financialWriteStorage(properties);
             var reads = configuration.snapshotFinancialReadOperations(writer, properties)) {
            JdbcTemplate ingestion = writer.jdbc();
            ingestion.execute("create table market_asset_catalog(dataset_code varchar(64) primary key)");
            ingestion.execute("create table market_quote_daily(id integer primary key, quote_name varchar(64))");
            ingestion.update("insert into market_quote_daily(id,quote_name) values (?,?)", 1, "SSE Composite");
            reads.publish();

            assertThat(reads.activeSlot()).isEqualTo("A");
            assertThat(reads.generation()).isEqualTo(1);
            assertThat(reads.queryForList("select quote_name from market_quote_daily where id=?", 1))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("quote_name", "SSE Composite"));

            ingestion.update("update market_quote_daily set quote_name=? where id=?", "Updated", 1);
            assertThat(reads.queryForList("select quote_name from market_quote_daily where id=?", 1))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("quote_name", "SSE Composite"));
            reads.publish();

            assertThat(reads.activeSlot()).isEqualTo("B");
            assertThat(reads.generation()).isEqualTo(2);
            assertThat(reads.queryForList("select quote_name from market_quote_daily where id=?", 1))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("quote_name", "Updated"));
            assertThat(Files.exists(tempDir.resolve("snapshots/read-a/financial-market.mv.db"))).isTrue();
            assertThat(Files.exists(tempDir.resolve("snapshots/read-b/financial-market.mv.db"))).isTrue();
            assertThatThrownBy(() -> reads.queryForList(
                "update market_quote_daily set quote_name='blocked' where id=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only accepts SELECT/WITH");
            ingestion.execute("drop table market_asset_catalog");
            assertThatThrownBy(reads::publish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to publish");
            assertThat(reads.activeSlot()).isEqualTo("B");
            assertThat(reads.generation()).isEqualTo(2);
            assertThat(reads.queryForList("select quote_name from market_quote_daily where id=?", 1))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("quote_name", "Updated"));
            assertThat(ingestion.queryForObject(
                "select quote_name from market_quote_daily where id=1", String.class)).isEqualTo("Updated");
        }
    }
}
