package com.chatchat.runtime.news;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NewsDatabaseSchemaTest {
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "news_source", "news_source_rule", "news_collect_record", "news_analysis_task",
        "market_asset_catalog", "data_schema_registry", "security_master",
        "market_quote_daily", "stock_valuation_daily", "index_valuation_daily",
        "margin_trade_daily", "stock_dividend_event", "etf_scale_daily",
        "market_statistics_daily", "bond_market_daily", "bond_market_overview_monthly",
        "bond_yield_curve_daily", "bond_counter_quote_daily", "bond_settlement_daily",
        "bond_collateral_monthly"
    );
    private static final Pattern CREATE_TABLE = Pattern.compile(
        "(?i)create\\s+table\\s+if\\s+not\\s+exists\\s+`?([a-z0-9_]+)`?");

    @Test
    void deploymentSchemasContainEveryNewsAndGovernedMarketTable() throws Exception {
        Path schemaRoot = Path.of("..", "database", "init").toAbsolutePath().normalize();
        Path mysql = schemaRoot.resolve("mysql/chatchat-runtime-news.sql");
        Path h2 = schemaRoot.resolve("h2/chatchat-runtime-news.sql");

        assertThat(tableNames(mysql)).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
        assertThat(tableNames(h2)).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);

        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:news_deployment_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new FileSystemResource(h2), StandardCharsets.UTF_8));
        }
        var jdbc = new JdbcTemplate(dataSource);
        Set<String> actual = Set.copyOf(jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE'",
            String.class));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    private Set<String> tableNames(Path script) throws Exception {
        var matcher = CREATE_TABLE.matcher(Files.readString(script, StandardCharsets.UTF_8));
        return matcher.results().map(result -> result.group(1).toLowerCase()).collect(Collectors.toUnmodifiableSet());
    }
}
