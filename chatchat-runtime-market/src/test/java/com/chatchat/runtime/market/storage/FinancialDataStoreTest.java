package com.chatchat.runtime.market.storage;

import com.chatchat.runtime.market.config.MarketModuleProperties;
import com.chatchat.runtime.market.model.MarketObservation;
import com.chatchat.runtime.market.model.MarketSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialDataStoreTest {
    @Test
    void resolvesOfficialSecurityNamesWithSqlLike() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:security_master;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var store = new FinancialDataStore(new JdbcTemplate(dataSource), dataSource,
            new ObjectMapper(), new MarketModuleProperties());
        store.initialize();
        store.replaceSecurityMaster("SSE", List.of(
            new FinancialDataStore.SecurityMasterRecord("600010", "包钢股份", "内蒙古包钢钢联股份有限公司",
                "STOCK", "主板A股", LocalDate.parse("2001-03-09"), "制造业", "https://www.sse.com.cn"),
            new FinancialDataStore.SecurityMasterRecord("600000", "浦发银行", "上海浦东发展银行股份有限公司",
                "STOCK", "主板A股", LocalDate.parse("1999-11-10"), "金融业", "https://www.sse.com.cn")));

        assertThat(store.findSecurityCodes("包钢", 5)).containsExactly("600010");
        assertThat(store.findSecurityCodes("内蒙古包钢", 5)).containsExactly("600010");
        assertThat(store.securityMasterCount()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void supportsGovernedLikeFilterForUnindexedQuoteNames() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:financial_quote_like;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var store = new FinancialDataStore(new JdbcTemplate(dataSource), dataSource,
            new ObjectMapper(), new MarketModuleProperties());
        store.initialize();
        MarketSource source = new MarketSource(8L, "sse_daily_snapshot", "上交所行情快照", "https://www.sse.com.cn");
        store.store(quote(source, "600010", "包钢股份", "2.18"));
        store.store(quote(source, "600000", "浦发银行", "10.50"));

        Map<String, Object> result = store.query("market_quote_daily", Map.of("quoteNameLike", "包钢"),
            LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), 10);

        assertThat(result).containsEntry("count", 1);
        assertThat((List<Map<String, Object>>) result.get("rows")).singleElement().satisfies(row ->
            assertThat(row).containsEntry("quote_code", "600010").containsEntry("quote_name", "包钢股份"));
    }

    @Test
    void resolvesQuestionEntitiesFromStoredDatasetWithoutLanguageRules() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:financial_entity_resolution;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var store = new FinancialDataStore(new JdbcTemplate(dataSource), dataSource,
            new ObjectMapper(), new MarketModuleProperties());
        store.initialize();
        MarketSource source = new MarketSource(8L, "exchange_quote", "交易所行情", "https://example.test");
        store.store(quote(source, "000001", "平安银行", "10.98"));
        store.replaceSecurityMaster("SZSE", List.of(
            new FinancialDataStore.SecurityMasterRecord("000001", "平安银行", "平安银行股份有限公司",
                "STOCK", "主板A股", LocalDate.parse("1991-04-03"), "金融业", "https://www.szse.cn")));

        assertThat(store.resolveEntityFilters(
            "market_quote_daily", "请告诉我平安银行现在交易价格与相关情况", 5))
            .containsExactly(Map.of("quote_code", "000001", "quote_name_like", "平安银行"));
        assertThat(store.assetSearchQuery("平安银行 当前的股价是多少", 5))
            .isEqualTo("当前的股价是多少");
    }

    @Test
    void refreshesBusinessDescriptionsAndFieldVocabularyWithoutNewIngestion() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:financial_catalog_vocabulary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        var store = new FinancialDataStore(jdbc, dataSource,
            new ObjectMapper(), new MarketModuleProperties());
        store.initialize();
        MarketSource source = new MarketSource(8L, "szse_daily_snapshot", "深交所行情快照",
            "https://www.szse.cn/market/trend/index.html");
        store.store(quote(source, "399001", "深证成指", "13241.00"));
        jdbc.update("update market_asset_catalog set asset_name='旧名称',business_description='旧描述',"
            + "business_tags_json='[]' where dataset_code='market_quote_daily'");

        assertThat(store.refreshCatalogDefinitions()).isEqualTo(1);

        assertThat(store.searchCatalog("A股收盘复盘", 10)).singleElement().satisfies(asset ->
            assertThat(asset)
                .containsEntry("asset_name", "证券、指数与A股收盘行情")
                .containsEntry("table_name", "market_quote_daily"));
        assertThat(jdbc.queryForObject("select business_description from data_schema_registry "
                + "where dataset_code=? and field_name=?", String.class, "market_quote_daily", "quote_code"))
            .isEqualTo("证券或指数代码");
    }

    @Test
    @SuppressWarnings("unchecked")
    void infersSchemaRegistersCatalogAndReturnsBoundedStructuredRows() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:financial_asset;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        var store = new FinancialDataStore(jdbc, dataSource, new ObjectMapper(), new MarketModuleProperties());
        store.initialize();
        MarketSource source = new MarketSource(7L, "sse_etf_scale", "上海证券交易所ETF规模",
            "https://www.sse.com.cn/etf");
        MarketObservation item = new MarketObservation(source, "ETF规模：测试ETF：10000", "规模数据", null,
            "上海证券交易所", "https://www.sse.com.cn/etf#510000-2026-07-21",
            Instant.parse("2026-07-21T08:00:00Z"), "zh-CN", List.of("ETF规模"), List.of("ETF"),
            Map.of("provider", "SSE", "dataset", "ETF规模", "scaleDate", "2026-07-21",
                "fundCode", "510000", "fundName", "测试ETF", "fundScale10KUnits", "12345.67"));

        FinancialDataStore.StoredObservation stored = store.store(item);

        assertThat(stored.datasetCode()).isEqualTo("etf_scale_daily");
        assertThat(jdbc.queryForObject("select count(*) from etf_scale_daily", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from market_asset_catalog", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select field_name,field_type from data_schema_registry where dataset_code=?",
            "etf_scale_daily")).anySatisfy(field -> assertThat(field).containsEntry("field_name", "fund_code"));
        Map<String, Object> result = store.query("etf_scale_daily", Map.of("fundCode", "510000"),
            LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), 10);
        assertThat(result).containsEntry("count", 1);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertThat(rows.get(0)).containsEntry("fund_code", "510000");
        assertThat((Map<String, Object>) result.get("asset"))
            .containsEntry("table_name", "etf_scale_daily")
            .containsEntry("dataset_code", "etf_scale_daily");
    }

    @Test
    void widensNumericColumnWhenExchangeLaterReturnsNotAvailableMarker() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:financial_asset_widening;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        var store = new FinancialDataStore(jdbc, dataSource, new ObjectMapper(), new MarketModuleProperties());
        store.initialize();
        MarketSource source = new MarketSource(140L, "three_market_overview", "Market overview", "https://example.test");

        store.store(statistics(source, "main", "507"));
        store.store(statistics(source, "gem", "n.a."));

        assertThat(jdbc.queryForObject("select count(*) from market_statistics_daily", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select field_type from data_schema_registry where dataset_code=? and field_name=?",
            String.class, "market_statistics_daily", "listed_h_shares")).isEqualTo("STRING");
        assertThat(jdbc.queryForList("select listed_h_shares from market_statistics_daily order by id"))
            .extracting(row -> String.valueOf(row.get("listed_h_shares")))
            .containsExactly("507.0000000000", "n.a.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void archivesWeeklySnapshotsBeforeDeletingDailyRowsAndAutoQueriesBothTiers() throws Exception {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:financial_retention;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        var properties = new MarketModuleProperties();
        properties.getRetention().setHotDays(7);
        properties.getRetention().setWeeklyArchiveDays(365);
        var store = new FinancialDataStore(jdbc, dataSource, new ObjectMapper(), properties);
        store.initialize();
        MarketSource source = new MarketSource(7L, "sse_etf_scale", "SSE ETF", "https://www.sse.com.cn/etf");

        store.storeAll(List.of(etf(source, "2026-07-01"), etf(source, "2026-07-03"),
            etf(source, "2026-07-10"), etf(source, "2026-07-20")));
        FinancialDataStore.RetentionRunResult retention = store.archiveAndCleanup(LocalDate.parse("2026-07-22"));

        assertThat(retention.archivedRows()).isEqualTo(2);
        assertThat(retention.deletedHotRows()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from etf_scale_daily", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select observation_date from etf_scale_daily_weekly_snapshot order by observation_date"))
            .extracting(row -> String.valueOf(row.get("observation_date")))
            .containsExactly("2026-07-03", "2026-07-10");

        Map<String, Object> result = store.query("etf_scale_daily", Map.of(), LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-22"), 10, "auto");
        assertThat(result).containsEntry("count", 3).containsEntry("history_mode", "auto");
        assertThat((List<String>) result.get("storage_tiers")).containsExactly("weekly_snapshot", "daily_hot");
        assertThat((Map<String, Object>) result.get("asset"))
            .containsEntry("archive_table_name", "etf_scale_daily_weekly_snapshot")
            .containsEntry("hot_retention_days", 7)
            .containsEntry("archive_retention_days", 365);
    }

    private MarketObservation etf(MarketSource source, String date) {
        return new MarketObservation(source, "ETF " + date, "scale", null, "SSE",
            "https://www.sse.com.cn/etf#510000-" + date, Instant.parse(date + "T08:00:00Z"), "zh-CN",
            List.of("ETF scale"), List.of("ETF"), Map.of("provider", "SSE", "dataset", "ETF规模",
                "scaleDate", date, "fundCode", "510000", "fundName", "ETF", "fundScale10KUnits", "100"));
    }

    private MarketObservation quote(MarketSource source, String code, String name, String close) {
        return new MarketObservation(source, name + "行情", "quote", null, "SSE",
            "https://www.sse.com.cn/quote#" + code, Instant.parse("2026-07-23T08:00:00Z"), "zh-CN",
            List.of("行情"), List.of("SSE"), Map.of(
                "datasetCode", "market_quote_daily", "tradeDate", "2026-07-23",
                "quoteCode", code, "quoteName", name, "close", close));
    }

    private MarketObservation statistics(MarketSource source, String segment, String listedHShares) {
        return new MarketObservation(source, "Market " + segment, null, null, "HKEX",
            "https://example.test#" + segment, Instant.parse("2026-07-21T08:00:00Z"), "zh-CN",
            List.of("Market statistics"), List.of("market"), Map.of(
                "provider", "HKEX", "dataset", "沪深港市场汇总", "tradeDate", "2026-07-21",
                "marketCode", "HKEX", "segment", segment, "listedHShares", listedHShares));
    }
}
