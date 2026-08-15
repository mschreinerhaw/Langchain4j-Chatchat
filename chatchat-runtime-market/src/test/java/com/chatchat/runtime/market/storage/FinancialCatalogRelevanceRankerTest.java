package com.chatchat.runtime.market.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialCatalogRelevanceRankerTest {

    @Test
    void ranksTheSemanticallyMatchingCatalogAheadOfHighEngineScoreNoise() {
        String query = "A股主要指数 2026年8月14日 行情数据 成交量";
        List<Map<String, Object>> candidates = List.of(
            asset("bond_market_overview_monthly", "中债市场统计概览",
                "债券托管量、投资者数量、发行与结算统计。", "中债,统计概览,发行量", 76.9D),
            asset("bond_collateral_monthly", "中债担保品业务月度数据",
                "担保品余额、客户数量与质押业务规模。", "中债,担保品,保证金", 54.8D),
            asset("margin_trade_daily", "融资融券每日数据",
                "融资买入、融资余额及融券余量数据。", "融资融券,杠杆资金,个券", 37.2D),
            asset("market_quote_daily", "证券、指数与A股收盘行情",
                "记录股票和主要指数的开盘、收盘、涨跌幅、成交量与成交额。",
                "A股收盘复盘,行情,主要指数,收盘点位,成交量", 26.2D),
            asset("index_valuation_daily", "指数行情与估值",
                "记录指数收盘行情、滚动市盈率与成交额。", "指数,估值,收盘", 18.0D));

        List<Map<String, Object>> ranked = FinancialCatalogRelevanceRanker.rank(query, candidates, 3);

        assertThat(ranked).extracting(item -> item.get("dataset_code"))
            .startsWith("market_quote_daily")
            .doesNotContain("bond_market_overview_monthly", "bond_collateral_monthly");
        assertThat(ranked.get(0))
            .containsEntry("search_engine_score", 26.2D)
            .containsEntry("relevance_strategy", "catalog_semantic_idf_ngram_v1");
    }

    @Test
    void usesRuntimeMetadataInsteadOfEmbeddedDomainVocabulary() {
        List<Map<String, Object>> candidates = List.of(
            asset("dynamic_alpha", "冷却设备台账", "设备位置与采购信息", "设备,采购", 20D),
            asset("dynamic_beta", "Thermal sensor latency", "Time-series sensor response latency",
                "thermal,sensor,latency", 1D));

        List<Map<String, Object>> ranked = FinancialCatalogRelevanceRanker.rank(
            "thermal sensor latency trend", candidates, 1);

        assertThat(ranked).singleElement().satisfies(item ->
            assertThat(item).containsEntry("dataset_code", "dynamic_beta"));
    }

    private Map<String, Object> asset(String code, String name, String description,
                                      String tags, double engineScore) {
        return Map.of(
            "dataset_code", code,
            "table_name", code,
            "asset_name", name,
            "business_description", description,
            "business_tags_json", tags,
            "relevance_score", engineScore);
    }
}
