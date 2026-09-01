package com.chatchat.mcpserver.news.tool;

import com.chatchat.mcpserver.news.financial.FinancialEnrichmentService;
import com.chatchat.mcpserver.news.runtime.NewsRuntimeClient;
import com.chatchat.mcpserver.news.runtime.NewsSearchService;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RemoteNewsMcpToolProviderTest {

    @Test
    void webSearchPublishesIndependentLocalNewsQueryTerms() {
        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(
            new NewsSearchService(mock(NewsRuntimeClient.class)), java.util.Optional.empty());

        var parameter = provider.definitions().stream().findFirst().orElseThrow().parameters().stream()
            .filter(item -> "queryTerms".equals(item.getName())).findFirst().orElseThrow();

        assertThat(parameter.getType()).isEqualTo("array");
        assertThat(parameter.getMetadata())
            .containsEntry("items", Map.of("type", "string"))
            .containsEntry("maxItems", 8);
        assertThat(provider.definitions().stream().findFirst().orElseThrow().parameters())
            .extracting(item -> item.getName())
            .contains("query", "queryTerms", "keywords", "intent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void newsSearchRemainsAvailableWhenFinancialEnrichmentIsNotInstalled() {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of(
            "results", List.of(Map.of("resultType", "news", "title", "announcement")))));
        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(
            new NewsSearchService(news), java.util.Optional.empty());

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "latest announcements")).build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData())
            .containsEntry("newsCount", 1)
            .containsEntry("financialAssetCount", 0)
            .containsEntry("financialEnrichmentSkippedReason", "capability_unavailable");
    }

    @Test
    void providerHasNoStorageLayerDependency() {
        assertThat(java.util.Arrays.stream(RemoteNewsMcpToolProvider.class.getDeclaredFields())
            .map(java.lang.reflect.Field::getType))
            .doesNotContain(FinancialDataStore.class, FinancialAssetCatalogService.class, NewsRuntimeClient.class);
        assertThat(java.util.Arrays.stream(RemoteNewsMcpToolProvider.class.getConstructors())
            .flatMap(constructor -> java.util.Arrays.stream(constructor.getParameterTypes())))
            .doesNotContain(FinancialDataStore.class, FinancialAssetCatalogService.class, NewsRuntimeClient.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void finalSummaryWebEnhancementNeverHydratesFinancialRows() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of(
            "results", List.of(Map.of("resultType", "news", "title", "latest announcement")))));
        when(market.search(any(), any(Integer.class))).thenReturn(List.of(
            Map.of("dataset_code", "market_quote_daily", "asset_name", "daily quotes")));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "latest company announcements"))
            .context(Map.of("internalPurpose", "final_summary_web_enhancement"))
            .build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData()).containsEntry("financialDatasetCount", 0);
        verify(market, never()).search(any(), any(Integer.class));
        verify(store, never()).assetSearchQuery(any(), any(Integer.class));
        verify(store, never()).resolveEntityFilters(any(), any(), any(Integer.class));
        verify(store, never()).query(any(), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void finalSummaryMarkerReturnsDynamicallySelectedFinancialRowsWithoutHardcodedDataset() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "authoritative opening observations";
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of(
            "results", List.of(Map.of("resultType", "web", "retrievalSource", "tencent_wsa",
                "title", "overnight market")) )));
        when(store.assetSearchQuery(query, 10)).thenReturn(query);
        when(market.search(query, 4)).thenReturn(List.of(
            Map.of("dataset_code", "new_runtime_dataset", "asset_name", "runtime market metrics")));
        when(store.resolveEntityFilters("new_runtime_dataset", query, 5)).thenReturn(List.of());
        when(store.query("new_runtime_dataset", Map.of(), null, null, 20, "auto"))
            .thenReturn(Map.of("rows", List.of(Map.of(
                "metric", "market_breadth", "value", 321, "payload_json", "must-not-cross-boundary"))));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", query, "num_results", 6, "financial_data_required", true))
            .context(Map.of("internalPurpose", "final_summary_web_enhancement"))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("financialDataRequired", true)
            .containsEntry("financialDataSatisfied", true)
            .containsEntry("financialDatasetCount", 1)
            .containsEntry("financialObservationCount", 1);
        assertThat((List<Map<String, Object>>) data.get("financialData")).singleElement().satisfies(dataset -> {
            assertThat(dataset).containsEntry("dataset", "new_runtime_dataset");
            assertThat((List<Map<String, Object>>) dataset.get("rows")).singleElement().satisfies(row ->
                assertThat(row).doesNotContainKey("payload_json").containsKey("_omitted_fields"));
        });
        verify(store).query("new_runtime_dataset", Map.of(), null, null, 20, "auto");
        verify(news).invoke(eq("web_search"), argThat(routed ->
            Integer.valueOf(1).equals(routed.getContext().get("upstreamLocalEvidenceCount"))));
    }

    @Test
    void localFinancialIndexRunsBeforeNewsRuntimeEvenWhenNewsIsCancelled() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(news.invoke(eq("web_search"), any()))
            .thenThrow(new CancellationException("outer tool deadline reached"));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.findExecutor("web_search")
                .orElseThrow().execute(ToolInput.builder().parameters(Map.of("query", "latest announcements")).build()))
            .isInstanceOf(CancellationException.class);

        var ordered = inOrder(market, news);
        ordered.verify(market).search(any(), any(Integer.class));
        ordered.verify(news).invoke(eq("web_search"), any());
        verify(store, never()).query(any(), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void directlyQueriesCompatibleQuoteIndexForCompanyQuestion() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "南方航空 当前的股价和最新资讯信息";
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of("results", List.of(
            Map.of("resultType", "news", "title", "南方航空最新资讯")))));
        when(market.search(query, 4)).thenReturn(List.of(
            Map.of("dataset_code", "market_quote_daily", "asset_name", "证券、指数与A股收盘行情")));
        when(store.resolveEntityFilters("market_quote_daily", query, 5)).thenReturn(List.of(
            Map.of("quote_code", "600029", "quote_name_like", "南方航空")));
        when(store.query(eq("market_quote_daily"),
            argThat(filters -> "600029".equals(filters.get("quote_code"))
                && "南方航空".equals(filters.get("quote_name_like"))),
            any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of(
                "quote_code", "600029", "quote_name", "南方航空", "close", 6.31))));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", query)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("newsCount", 1)
            .containsEntry("financialSearchQuery", query)
            .containsEntry("financialSearchQueryAligned", true)
            .containsEntry("financialSearchTool", "financial_data_search")
            .containsEntry("financialSearchToolVisibility", "internal_bridge_only")
            .containsEntry("result_type", "unified_search_results")
            .containsEntry("retrieval_stage", "DISCOVERY")
            .containsEntry("sample_only", false)
            .containsEntry("requires_second_query", false)
            .containsEntry("financialDatasetCount", 1)
            .containsEntry("financialObservationCount", 1)
            .containsEntry("financialDataPolicy", "local_first_auto")
            .containsEntry("financialDataAutoRetrieved", true);
        assertThat((List<Map<String, Object>>) data.get("financialData")).singleElement()
            .satisfies(dataset -> assertThat((List<Map<String, Object>>) dataset.get("rows"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("close", 6.31)));
        assertThat((List<Map<String, Object>>) data.get("financialAssets")).singleElement().satisfies(asset -> {
            assertThat(asset).containsEntry("dataset", "market_quote_daily");
            assertThat((Map<String, Object>) asset.get("followUp"))
                .containsEntry("tool", "web_search");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsPingAnQuoteWithoutDependingOnModelSecondQuery() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "平安银行 当前的股价是多少";
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of("results", List.of())));
        when(market.search(query, 4)).thenReturn(List.of(
            Map.of("dataset_code", "market_quote_daily", "asset_name", "证券、指数与A股收盘行情")));
        when(store.resolveEntityFilters("market_quote_daily", query, 5)).thenReturn(List.of(
            Map.of("quote_code", "000001", "quote_name_like", "平安银行")));
        when(store.query(eq("market_quote_daily"),
            argThat(filters -> filters.size() == 2
                && "000001".equals(filters.get("quote_code"))
                && "平安银行".equals(filters.get("quote_name_like"))),
            any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of(
                "quote_code", "000001", "quote_name", "平安银行", "close", 10.98))));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", query)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("retrieval_stage", "DISCOVERY")
            .containsEntry("sample_only", false)
            .containsEntry("requires_second_query", false)
            .containsEntry("financialDatasetCount", 1)
            .containsEntry("financialObservationCount", 1)
            .containsKey("discovery_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void directlyQueriesHighestRankedCompatibleDatasetWithoutHardcodedSelection() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "包钢股份（600010）行情查询";
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of("results", List.of())));
        when(market.search(query, 4)).thenReturn(List.of(
            Map.of("dataset_code", "market_quote_daily", "asset_name", "证券及指数行情"),
            Map.of("dataset_code", "index_valuation_daily", "asset_name", "指数行情与估值"),
            Map.of("dataset_code", "market_statistics_daily", "asset_name", "市场统计")));
        when(store.resolveEntityFilters("market_quote_daily", query, 5)).thenReturn(List.of(
            Map.of("quote_code", "600010", "quote_name_like", "包钢股份")));
        when(store.query(eq("market_quote_daily"),
            argThat(filters -> "600010".equals(filters.get("quote_code"))
                && "包钢股份".equals(filters.get("quote_name_like"))),
            any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of(
                "quote_code", "600010", "quote_name", "包钢股份", "close", 2.18,
                "change_pct", "1.40%", "volume", 123456L))));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", query)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("financialAssetCount", 3)
            .containsEntry("financialDatasetCount", 1)
            .containsEntry("financialObservationCount", 1)
            .containsEntry("requires_second_query", false);
        assertThat((List<Map<String, Object>>) data.get("financialAssets"))
            .extracting(item -> item.get("dataset"))
            .containsExactly("market_quote_daily", "index_valuation_daily", "market_statistics_daily");
    }

    @Test
    void exposesOnlyUnifiedWebSearch() {
        RemoteNewsMcpToolProvider provider = provider(
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
        when(market.search("ETF规模", 4)).thenReturn(List.of(Map.of(
            "dataset_code", "etf_scale_daily", "asset_name", "ETF规模",
            "business_description", "ETF份额、净值与资产规模",
            "database_name", "chatchat_market", "table_name", "etf_scale_daily",
            "relevance_score", 12.5D, "update_frequency", "每日",
            "last_observation_date", "2026-07-23",
            "fields", List.of(Map.of("field_name", "fund_code", "field_type", "STRING",
                "business_description", "基金代码")))));
        when(store.query(eq("etf_scale_daily"), any(), any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of("fund_code", "510300", "scale10_k_units", 12345))));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "ETF规模")).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("newsCount", 1).containsEntry("financialAssetCount", 1)
            .containsEntry("financialDatasetCount", 1).containsEntry("financialObservationCount", 1)
            .containsEntry("result_type", "unified_search_results")
            .containsEntry("sample_only", false).containsEntry("requires_second_query", false)
            .containsEntry("count", 3);
        assertThat((Map<String, Object>) data.get("financialIndex"))
            .containsEntry("name", "financial-data-asset")
            .containsEntry("contractVersion", "financial_index_capability_v1")
            .containsEntry("compatibleDirectQuery", true)
            .containsKeys("supportedScenarios", "availableFieldsByDataset", "queryRevisionHint")
            .containsKey("secondStage");
        assertThat((List<Map<String, Object>>) data.get("financialAssets")).singleElement().satisfies(asset -> {
            assertThat(asset).containsEntry("dataset", "etf_scale_daily")
                .containsEntry("updateFrequency", "每日")
                .containsEntry("lastObservationDate", "2026-07-23")
                .containsEntry("storageLocation", "chatchat_market.etf_scale_daily")
                .containsEntry("relevanceScore", 12.5D)
                .containsEntry("readTool", "web_search");
            assertThat((List<Map<String, Object>>) asset.get("availableFields"))
                .containsExactly(Map.of("name", "fund_code", "type", "STRING", "description", "基金代码",
                    "exactFilterKey", "fund_code", "containsFilterKey", "fund_code_like"));
            assertThat((Map<String, Object>) asset.get("followUp"))
                .containsEntry("tool", "web_search")
                .satisfies(followUp -> assertThat((Map<String, Object>) followUp.get("arguments"))
                    .containsKeys("dataset", "discovery_id"));
        });
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).extracting(item -> item.get("resultType"))
            .containsExactly("financial_dataset_query", "news", "financial_data_asset");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compatibleIndexReadsRowsFromMultipleRankedDatasets() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of("results", List.of())));
        when(market.search("上证指数和沪深300走势", 4)).thenReturn(List.of(
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
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", "上证指数和沪深300走势")).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("financialAssetCount", 3)
            .containsEntry("financialDatasetCount", 2)
            .containsEntry("financialObservationCount", 2)
            .containsEntry("requires_second_query", false);
        List<Map<String, Object>> financialAssets = (List<Map<String, Object>>) data.get("financialAssets");
        assertThat(financialAssets).extracting(item -> item.get("dataset"))
            .containsExactly("market_quote_daily", "index_valuation_daily", "market_statistics_daily");
        assertThat((List<Map<String, Object>>) data.get("financialData"))
            .extracting(item -> item.get("dataset"))
            .containsExactly("market_quote_daily", "index_valuation_daily");
    }

    @Test
    void extractsRequestedDateAndAppliesItToCompatibleIndexQuery() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "生成2026年7月23日A股收盘复盘";
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of("results", List.of())));
        when(market.search(query, 4)).thenReturn(List.of(
            Map.of("dataset_code", "market_quote_daily", "asset_name", "A股行情")));
        when(store.query(eq("market_quote_daily"), eq(Map.of()),
            eq(java.time.LocalDate.parse("2026-07-23")), eq(java.time.LocalDate.parse("2026-07-23")),
            any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of("observation_date", "2026-07-23", "close", 3600))));
        RemoteNewsMcpToolProvider provider = provider(news, market, store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("query", query)).build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData())
            .containsEntry("financialObservationCount", 1)
            .containsEntry("structuredObservationCount", 1)
            .containsEntry("requires_second_query", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void compactsExplicitDatasetQueriesBeforeReturningModelContext() {
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(store.query(eq("index_valuation_daily"), any(), any(), any(), any(Integer.class), eq("auto")))
            .thenReturn(Map.of("rows", List.of(Map.of("record_key", "1", "close", 3864.37,
                "payload_json", "{raw}", "pe_ttm_history", "[history]"))));
        RemoteNewsMcpToolProvider provider = provider(
            mock(NewsRuntimeClient.class), mock(FinancialAssetCatalogService.class), store);

        ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(ToolInput.builder()
            .parameters(Map.of("dataset", "index_valuation_daily", "discovery_id", "discovery-123")).build());

        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("resultView", "compact_model_context")
            .containsEntry("result_type", "financial_dataset_query")
            .containsEntry("retrieval_stage", "EXECUTION")
            .containsEntry("sample_only", false)
            .containsEntry("requires_second_query", false)
            .containsEntry("discovery_id", "discovery-123")
            .containsEntry("count", 1);
        assertThat((List<Map<String, Object>>) data.get("rows")).singleElement().satisfies(row -> {
            assertThat(row).doesNotContainKeys("payload_json", "pe_ttm_history")
                .containsEntry("close", 3864.37);
        });
    }

    private RemoteNewsMcpToolProvider provider(NewsRuntimeClient news,
                                               FinancialAssetCatalogService market,
                                               FinancialDataStore store) {
        return new RemoteNewsMcpToolProvider(
            new NewsSearchService(news), java.util.Optional.of(new FinancialEnrichmentService(market, store)));
    }
}
