package com.chatchat.runtime.market.analysis;

import com.chatchat.runtime.market.storage.FinancialReadOperations;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialMarketQueryExecutorTest {

    private FinancialMarketQueryExecutor executor;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:financial-query-" + System.nanoTime()
            + ";DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
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
    void usesIsolatedReadLaneForFinancialAnalysisSql() {
        AtomicBoolean isolatedLaneCalled = new AtomicBoolean();
        FinancialReadOperations isolatedReads = new FinancialReadOperations() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
                throw new AssertionError("positional read was not expected");
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql,
                                                          Map<String, Object> parameters,
                                                          int maxRows,
                                                          int timeoutSeconds) {
                isolatedLaneCalled.set(true);
                return List.of(Map.of("dataset_code", "market_quote_daily"));
            }
        };
        FinancialMarketQueryExecutor isolatedExecutor =
            new FinancialMarketQueryExecutor(jdbc.getDataSource(), isolatedReads);

        FinancialMarketQueryExecutor.QueryResult result = isolatedExecutor.execute(
            "select dataset_code from market_asset_catalog", Map.of(), 20, 10);

        assertThat(isolatedLaneCalled).isTrue();
        assertThat(result.rows()).singleElement().satisfies(row ->
            assertThat(row).containsEntry("dataset_code", "market_quote_daily"));
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
    void exposesSevenCompleteDisabledSampleDefinitions() {
        assertThat(FinancialAnalysisQuerySamples.all()).hasSize(7);
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
            assertThat(sample.sql())
                .contains("ROW_NUMBER() OVER", "observation_rank")
                .doesNotContain("QUALIFY ")
                .doesNotContain("IFNULL(", "DATE_FORMAT(", "STR_TO_DATE(", "LIMIT ");
        });
    }

    @Test
    void everyBuiltInAnalysisTemplateExecutesAgainstNativeH2() {
        createNativeH2FinancialTables();

        FinancialAnalysisQuerySamples.all().forEach(sample -> {
            Map<String, Object> parameters = sample.inputSchema().toString().contains("security_code")
                ? Map.of("security_code", "000001") : Map.of();
            FinancialMarketQueryExecutor.QueryResult result =
                executor.execute(sample.sql(), parameters, sample.maxRows(), 10);

            assertThat(result.rows()).as(sample.toolName()).isEmpty();
            assertThat(result.dataAvailable()).as(sample.toolName()).isTrue();
            assertThat(result.availabilityStatus()).as(sample.toolName()).isEqualTo("AVAILABLE");
        });
    }

    @Test
    void everyBuiltInAnalysisTemplateReportsUncollectedDatasetInsteadOfBadSqlGrammar() {
        FinancialAnalysisQuerySamples.all().forEach(sample -> {
            Map<String, Object> parameters = sample.inputSchema().toString().contains("security_code")
                ? Map.of("security_code", "000001") : Map.of();

            FinancialMarketQueryExecutor.QueryResult result =
                executor.execute(sample.sql(), parameters, sample.maxRows(), 10);

            assertThat(result.rows()).as(sample.toolName()).isEmpty();
            assertThat(result.columns()).as(sample.toolName()).isNotEmpty();
            assertThat(result.dataAvailable()).as(sample.toolName()).isFalse();
            assertThat(result.availabilityStatus()).as(sample.toolName())
                .isEqualTo("DATASET_NOT_COLLECTED_OR_SCHEMA_INCOMPLETE");
            assertThat(result.availabilityMessage()).as(sample.toolName()).contains("H2 read store");
        });
    }

    @Test
    void syntaxErrorsAreNotMisreportedAsMissingFinancialData() {
        assertThatThrownBy(() -> executor.execute(
            "select dataset_code,, asset_name from market_asset_catalog", Map.of(), 20, 10))
            .isInstanceOf(RuntimeException.class);
    }

    private void createNativeH2FinancialTables() {
        jdbc.execute("""
            create table market_quote_daily (
                id bigint primary key, observation_date date, collected_at timestamp,
                source_code varchar(64), source_url varchar(1000), quote_code varchar(32),
                quote_name varchar(160), instrument_type varchar(32), previous_close decimal(20,4),
                open decimal(20,4), high decimal(20,4), low decimal(20,4), close decimal(20,4),
                change_pct decimal(20,4), volume10_k_units decimal(20,4),
                amount10_k_cny decimal(20,4), amount100_m_cny decimal(20,4)
            )
            """);
        jdbc.execute("""
            create table etf_scale_daily (
                id bigint primary key, observation_date date, collected_at timestamp,
                source_code varchar(64), source_url varchar(1000), fund_code varchar(32),
                fund_scale10_k_units decimal(20,4), payload_json clob
            )
            """);
        jdbc.execute("""
            create table margin_trade_daily (
                id bigint primary key, observation_date date, collected_at timestamp,
                source_code varchar(64), source_url varchar(1000), record_key varchar(64), payload_json clob
            )
            """);
        jdbc.execute("""
            create table market_statistics_daily (
                id bigint primary key, observation_date date, collected_at timestamp,
                source_code varchar(64), source_url varchar(1000), record_key varchar(64), payload_json clob
            )
            """);
        jdbc.execute("""
            create table bond_yield_curve_daily (
                id bigint primary key, observation_date date, collected_at timestamp,
                source_code varchar(64), source_url varchar(1000), curve_name varchar(160),
                curve_type varchar(64), maturity_years decimal(12,4), yield_pct decimal(20,8)
            )
            """);
        jdbc.execute("""
            create table bond_settlement_daily (
                id bigint primary key, observation_date date, collected_at timestamp,
                source_code varchar(64), source_url varchar(1000), settlement_time varchar(64),
                settlement_type varchar(160), principal_amount100_m_cny decimal(20,4),
                face_amount100_m_cny decimal(20,4), funds_amount100_m_cny decimal(20,4),
                transaction_count bigint
            )
            """);
    }
}
