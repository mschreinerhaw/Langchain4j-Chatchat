package com.chatchat.runtime.market.analysis;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialMarketQueryExecutorTest {

    private FinancialMarketQueryExecutor executor;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:financial-query-" + System.nanoTime()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            create table market_asset_catalog (
                dataset_code varchar(64) primary key,
                asset_name varchar(200),
                last_observation_date date
            )
            """);
        executor = new FinancialMarketQueryExecutor(dataSource);
    }

    @Test
    void queriesOnlyGovernedFinancialTablesAndPreservesNullValues() {
        jdbc.update("insert into market_asset_catalog(dataset_code,asset_name,last_observation_date) values(?,?,?)",
            "market_quote_daily", "证券行情", null);

        FinancialMarketQueryExecutor.QueryResult result = executor.execute(
            "select dataset_code, asset_name, last_observation_date from market_asset_catalog "
                + "where dataset_code=:dataset",
            Map.of("dataset", "market_quote_daily"), 20, 10);

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows().get(0))
            .containsEntry("dataset_code", "market_quote_daily")
            .containsEntry("asset_name", "证券行情")
            .containsEntry("last_observation_date", null);
        assertThat(result.possiblyTruncated()).isFalse();
    }

    @Test
    void rejectsConfigurationTablesAndCommaJoinBypasses() {
        assertThatThrownBy(() -> executor.execute(
            "select * from mcp_database_query_config", Map.of(), 20, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("禁止访问非金融治理表");

        assertThatThrownBy(() -> executor.execute(
            "select a.dataset_code from market_asset_catalog a, mcp_database_query_config c",
            Map.of(), 20, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不允许逗号连接表");
    }

    @Test
    void exposesEightCompleteDisabledSampleDefinitions() {
        assertThat(FinancialAnalysisQuerySamples.all()).hasSize(8);
        assertThat(FinancialAnalysisQuerySamples.all()).allSatisfy(sample -> {
            assertThat(sample.id()).isNotBlank();
            assertThat(sample.toolName()).startsWith("sample_");
            assertThat(sample.title()).isNotBlank();
            assertThat(sample.description()).hasSizeGreaterThan(30);
            assertThat(sample.implementationSteps()).contains("1.");
            assertThat(sample.sql()).startsWithIgnoringCase("SELECT");
            assertThat(sample.inputSchema()).containsKeys("type", "properties", "required", "additionalProperties");
            assertThat(sample.tags()).isNotEmpty();
            assertThat(sample.intent()).isNotBlank();
            assertThat(sample.resultSemantics()).isNotBlank();
        });
    }
}
