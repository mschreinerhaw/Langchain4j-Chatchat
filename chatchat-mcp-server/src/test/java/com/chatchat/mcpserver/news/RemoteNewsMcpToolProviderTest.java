package com.chatchat.mcpserver.news;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteNewsMcpToolProviderTest {

    @Test
    void exposesOnlyUnifiedWebSearch() {
        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(
            mock(NewsRuntimeClient.class), mock(FinancialAssetCatalogService.class), mock(FinancialDataStore.class));

        assertThat(provider.definitions())
            .extracting(McpToolDefinition::name)
            .containsExactly("web_search");
        assertThat(provider.findExecutor("web_search")).isPresent();
        assertThat(provider.findExecutor("news_search")).isEmpty();
        assertThat(provider.findExecutor("news_latest")).isEmpty();
        assertThat(provider.findExecutor("news_source_status")).isEmpty();
        assertThat(provider.findExecutor("search_financial_dataset")).isEmpty();
        assertThat(provider.findExecutor("get_financial_data")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesNewsAndFinancialAssetCatalogBehindWebSearch() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of("results", List.of(Map.of(
            "resultType", "news", "title", "ETF资金流向", "url", "https://example.com/news")))));
        when(market.search("ETF规模", 10)).thenReturn(List.of(Map.of(
            "dataset_code", "etf_scale_daily", "asset_name", "ETF规模",
            "business_description", "ETF份额、净值与资产规模",
            "database_name", "chatchat_market", "table_name", "etf_scale_daily")));
        when(store.query(eq("etf_scale_daily"), any(), any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of("fund_code", "510300", "scale10_k_units", 12345))));
        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "ETF规模")).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("newsCount", 1).containsEntry("financialAssetCount", 1)
            .containsEntry("financialDatasetCount", 1).containsEntry("financialObservationCount", 1)
            .containsEntry("count", 3);
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).extracting(item -> item.get("resultType"))
            .containsExactly("news", "financial_data", "financial_data_asset");
        assertThat(results.get(2)).containsEntry("dataset", "etf_scale_daily")
            .containsEntry("storageLocation", "chatchat_market.etf_scale_daily")
            .containsEntry("readTool", "web_search");
    }

    @Test
    @SuppressWarnings("unchecked")
    void automaticallyReadsRelevantAShareDatasetsAndCompactsModelRows() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of("results", List.of())));
        when(market.search("上证指数和沪深300走势", 10)).thenReturn(List.of(
            Map.of("dataset_code", "market_quote_daily", "asset_name", "每日行情"),
            Map.of("dataset_code", "index_valuation_daily", "asset_name", "指数估值"),
            Map.of("dataset_code", "market_statistics_daily", "asset_name", "市场统计")));
        when(store.query(eq("market_quote_daily"), any(), any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of(
                "record_key", "quote-1", "observation_date", "2026-07-22", "quote_name", "上证指数",
                "close", 3867.20, "payload_json", "{very large raw json}"))));
        when(store.query(eq("index_valuation_daily"), any(), any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of(
                "record_key", "index-1", "observation_date", "2026-07-21", "index_name", "沪深300",
                "close", 4739.23, "change_pct", "3.06%", "close_history", "[large history]",
                "payload_json", "{very large raw json}"))));
        when(store.query(eq("market_statistics_daily"), any(), any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of(
                "record_key", "stats-1", "observation_date", "2026-07-22", "total_market_value", 644996.68))));
        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "上证指数和沪深300走势")).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("financialDatasetCount", 3)
            .containsEntry("financialObservationCount", 3);
        List<Map<String, Object>> financialData = (List<Map<String, Object>>) data.get("financialData");
        assertThat(financialData).extracting(item -> item.get("dataset"))
            .containsExactly("market_quote_daily", "index_valuation_daily", "market_statistics_daily");
        List<Map<String, Object>> valuationRows = (List<Map<String, Object>>) financialData.get(1).get("rows");
        assertThat(valuationRows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("close", 4739.23).containsEntry("change_pct", "3.06%");
            assertThat(row).doesNotContainKeys("payload_json", "close_history");
            assertThat(row.get("_omitted_fields")).asList().contains("payload_json", "close_history");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void compactsExplicitDatasetQueriesBeforeReturningModelContext() {
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(store.query(eq("index_valuation_daily"), any(), any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of("record_key", "1", "close", 3864.37,
                "payload_json", "{raw}", "pe_ttm_history", "[history]"))));
        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(
            mock(NewsRuntimeClient.class), mock(FinancialAssetCatalogService.class), store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("dataset", "index_valuation_daily")).build());

        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("resultView", "compact_model_context").containsEntry("count", 1);
        assertThat((List<Map<String, Object>>) data.get("rows")).singleElement().satisfies(row -> {
            assertThat(row).doesNotContainKeys("payload_json", "pe_ttm_history")
                .containsEntry("close", 3864.37);
        });
    }
}
