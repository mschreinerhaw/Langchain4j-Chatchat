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
    void builtInMarketQueryKeepsOnlyLatestStableObservation() {
        jdbc.execute("""
            create table market_quote_daily (
                id bigint primary key,
                observation_date date,
                collected_at timestamp,
                source_code varchar(64),
                source_url varchar(1000),
                quote_code varchar(32),
                quote_name varchar(160),
                instrument_type varchar(32),
                previous_close decimal(20,4),
                open decimal(20,4),
                high decimal(20,4),
                low decimal(20,4),
                close decimal(20,4),
                change_pct decimal(20,4),
                volume10_k_units decimal(20,4),
                amount10_k_cny decimal(20,4),
                amount100_m_cny decimal(20,4)
            )
            """);
        jdbc.update("""
            insert into market_quote_daily(
                id,observation_date,collected_at,source_code,source_url,quote_code,quote_name,instrument_type,close,change_pct
            ) values(?,?,?,?,?,?,?,?,?,?)
            """, 1L, java.sql.Date.valueOf("2026-07-27"), java.sql.Timestamp.valueOf("2026-07-27 11:30:00"),
            "sse_daily_snapshot", "https://example.test/index#observation=000001", "000001", "上证指数",
            "INDEX", 3829.38, 0.40);
        jdbc.update("""
            insert into market_quote_daily(
                id,observation_date,collected_at,source_code,source_url,quote_code,quote_name,instrument_type,close,change_pct
            ) values(?,?,?,?,?,?,?,?,?,?)
            """, 2L, java.sql.Date.valueOf("2026-07-27"), java.sql.Timestamp.valueOf("2026-07-27 15:30:00"),
            "sse_daily_snapshot", "https://example.test/index#observation=000001", "000001", "上证指数",
            "INDEX", 3858.25, 1.15);
        String sql = FinancialAnalysisQuerySamples.all().stream()
            .filter(sample -> "sample_market_latest_movers".equals(sample.toolName()))
            .findFirst().orElseThrow().sql();

        FinancialMarketQueryExecutor.QueryResult result = executor.execute(sql, Map.of(), 200, 10);

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.get("quote_code")).isEqualTo("000001");
            assertThat(String.valueOf(row.get("close"))).isEqualTo("3858.2500");
        });
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
