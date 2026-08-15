package com.chatchat.e2e;

import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.mcpserver.news.FinancialEnrichmentService;
import com.chatchat.mcpserver.news.FinancialDataMcpToolProvider;
import com.chatchat.mcpserver.news.FinancialQueryCacheService;
import com.chatchat.mcpserver.news.FinancialQueryCacheConfigService;
import com.chatchat.mcpserver.cache.McpCacheProperties;
import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.mcpserver.cache.RedisCacheStore;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.news.NewsRuntimeClient;
import com.chatchat.mcpserver.news.NewsSearchService;
import com.chatchat.mcpserver.news.RemoteNewsMcpToolProvider;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import com.chatchat.runtime.market.config.MarketModuleProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Release regression for explicit, non-hardcoded financial retrieval behind unified web_search. */
class ProductionFinancialRetrievalIntentMarkerE2E {

    @Test
    @SuppressWarnings("unchecked")
    void dedicatedFinancialToolPreservesJointNewsAndDataEvidenceWithoutDuplicateDatabaseRead() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "2026年8月15日 A股收盘复盘 主要指数 涨跌幅 成交量";
        LocalDate requestedDay = LocalDate.of(2026, 8, 15);
        String runtimeDataset = "runtime_dataset_" + UUID.randomUUID().toString().replace("-", "");
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of(
            "results", List.of(Map.of("resultType", "news", "title", "policy and announcement evidence")))));
        when(store.assetSearchQuery(query, 10)).thenReturn(query);
        when(catalog.search(query, 4)).thenReturn(List.of(Map.of(
            "dataset_code", runtimeDataset, "asset_name", "runtime-discovered observations")));
        when(catalog.search(query, 3)).thenReturn(List.of(Map.of(
            "dataset_code", runtimeDataset, "asset_name", "runtime-discovered observations")));
        when(store.resolveEntityFilters(runtimeDataset, query, 5)).thenReturn(List.of());
        when(store.query(runtimeDataset, Map.of(), requestedDay, requestedDay, 20, "auto")).thenReturn(Map.of(
            "rows", List.of(Map.of("metric", "market_breadth", "value", 321))));
        McpRocksDbStore rocks = mock(McpRocksDbStore.class);
        RedisCacheStore redis = mock(RedisCacheStore.class);
        AtomicReference<byte[]> cached = new AtomicReference<>();
        when(rocks.isUsable()).thenReturn(true);
        when(rocks.get(anyString())).thenAnswer(invocation -> cached.get());
        doAnswer(invocation -> { cached.set(invocation.getArgument(1)); return null; })
            .when(rocks).put(anyString(), any(byte[].class));
        FinancialQueryCacheService cache = new FinancialQueryCacheService(
            new MarketModuleProperties(), new McpCacheProperties(), rocks, redis,
            new ObjectMapper().findAndRegisterModules(), mock(FinancialQueryCacheConfigService.class));
        FinancialEnrichmentService financial = new FinancialEnrichmentService(catalog, store, cache);
        RemoteNewsMcpToolProvider webProvider = new RemoteNewsMcpToolProvider(
            new NewsSearchService(news), Optional.of(financial));
        FinancialDataMcpToolProvider financialProvider = new FinancialDataMcpToolProvider(financial);
        String webTool = "mcp_chatchat_mcp_server_web_search";
        String financialTool = "mcp_chatchat_mcp_server_financial_data_search";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(webTool)).thenReturn(ToolMetadata.builder()
            .id(webTool).title("Web search").categories(List.of("mcp"))
            .parameters(List.of(com.chatchat.common.tool.ToolParameter.builder()
                .name("financial_data_required").type("boolean").build()))
            .build());
        when(registry.getToolMetadata(financialTool)).thenReturn(ToolMetadata.builder()
            .id(financialTool).title("Local financial data").categories(List.of("mcp")).build());
        when(registry.executeEnhancedTool(eq(webTool), any())).thenAnswer(invocation -> {
            try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context("tenant-e2e"))) {
                return webProvider.findExecutor("web_search").orElseThrow().execute(invocation.getArgument(1));
            }
        });
        when(registry.executeEnhancedTool(eq(financialTool), any())).thenAnswer(invocation -> {
            try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context("tenant-e2e"))) {
                return financialProvider.findExecutor("financial_data_search").orElseThrow()
                    .execute(invocation.getArgument(1));
            }
        });
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), new ToolRuntimeProperties(), List.of(), List.of());
        try {
            Map<String, Object> attributes = Map.of(
                "requiredToolParameters", Map.of(
                    webTool, Map.of("financial_data_required", true)));
            ToolRuntimeExecution webExecution = runtime.execute(ToolRuntimeRequest.builder()
                .toolName(webTool).runtimeMode("agent_chat").requestId("joint-web")
                .conversationId("joint-conversation").tenantId("tenant-e2e").userId("user-e2e")
                .allowedTools(List.of(webTool, financialTool)).attributes(attributes)
                .toolInput(ToolInput.builder().parameters(Map.of(
                    "query", query, "financial_data_required", false)).build()).build());
            Map<String, Object> webData = (Map<String, Object>) webExecution.output().getData();
            assertThat(webData).containsEntry("newsCount", 1)
                .containsEntry("financialDatasetCount", 1)
                .containsEntry("financialDataSatisfied", true);
            assertThat(webExecution.audit())
                .containsEntry("runtimeRequiredToolParametersApplied",
                    List.of("financial_data_required"));

            ToolRuntimeExecution financialExecution = runtime.execute(ToolRuntimeRequest.builder()
                .toolName(financialTool).runtimeMode("agent_chat").requestId("joint-financial")
                .conversationId("joint-conversation").tenantId("tenant-e2e").userId("user-e2e")
                .allowedTools(List.of(webTool, financialTool)).attributes(attributes)
                .toolInput(ToolInput.builder().parameters(Map.of("query", query)).build()).build());
            Map<String, Object> financialData = (Map<String, Object>) financialExecution.output().getData();
            assertThat(financialData).containsEntry("retrievalSource", "governed_financial_store")
                .containsEntry("networkSearchUsed", false)
                .containsEntry("datasetCount", 1)
                .containsEntry("observationCount", 1);
            verify(store).query(runtimeDataset, Map.of(), requestedDay, requestedDay, 20, "auto");
        } finally {
            runtime.shutdown();
        }
    }

    private McpInvocationContext.Context context(String tenant) {
        return new McpInvocationContext.Context("e2e", null, null, "request", null,
            "user", null, tenant, null, null, null, null, null, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrievalMarkerControlsBoundedFinancialReadAndDatasetComesOnlyFromRuntimeCatalog() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "隔夜全球市场与今日开盘结构化观察";
        String runtimeDataset = "tenant_dataset_" + UUID.randomUUID().toString().replace("-", "");
        when(news.invoke(eq("web_search"), any())).thenReturn(ToolOutput.success(Map.of(
            "results", List.of(Map.of("resultType", "web", "retrievalSource", "tencent_wsa",
                "title", "overnight market evidence", "url", "https://example.test/market")) )));

        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(
            new NewsSearchService(news), Optional.of(new FinancialEnrichmentService(catalog, store)));
        ToolInput withoutMarker = ToolInput.builder()
            .parameters(Map.of("query", query, "num_results", 6))
            .context(Map.of("internalPurpose", FinancialEnrichmentService.FINAL_SUMMARY_PURPOSE))
            .build();

        ToolOutput newsOnly = provider.findExecutor("web_search").orElseThrow().execute(withoutMarker);
        assertThat(newsOnly.isSuccess()).isTrue();
        assertThat((Map<String, Object>) newsOnly.getData())
            .containsEntry("financialDataRequired", false)
            .containsEntry("financialDatasetCount", 0);
        verify(catalog, never()).search(any(), any(Integer.class));
        verify(store, never()).query(any(), any(), any(), any(), any(Integer.class), any());

        when(store.assetSearchQuery(query, 10)).thenReturn(query);
        when(catalog.search(query, 4)).thenReturn(List.of(Map.of(
            "dataset_code", runtimeDataset, "asset_name", "tenant registered opening metrics")));
        when(store.resolveEntityFilters(runtimeDataset, query, 5)).thenReturn(List.of());
        when(store.query(runtimeDataset, Map.of(), null, null, 20, "auto")).thenReturn(Map.of(
            "rows", List.of(Map.of("metric", "market_breadth", "value", 456))));
        ToolInput modelInputWithoutMarker = ToolInput.builder()
            .parameters(Map.of(
                "query", query,
                "num_results", 6,
                "financial_data_required", false,
                "financial_dataset_limit", 2,
                "financial_row_limit", 20))
            .context(Map.of("internalPurpose", FinancialEnrichmentService.FINAL_SUMMARY_PURPOSE))
            .build();

        String localToolName = "mcp_runtime_registered_web_search";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(localToolName)).thenReturn(ToolMetadata.builder()
            .id(localToolName).title("Runtime registered web search").categories(List.of("mcp"))
            .parameters(List.of(com.chatchat.common.tool.ToolParameter.builder()
                .name("financial_data_required").type("boolean").build()))
            .build());
        when(registry.executeEnhancedTool(eq(localToolName), any())).thenAnswer(invocation ->
            provider.findExecutor("web_search").orElseThrow().execute(invocation.getArgument(1)));
        ToolRuntimeProperties runtimeProperties = new ToolRuntimeProperties();
        runtimeProperties.setDefaultRetryAttempts(0);
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), runtimeProperties, List.of(), List.of());
        try {
            ToolRuntimeExecution execution = runtime.execute(ToolRuntimeRequest.builder()
                .toolName(localToolName).runtimeMode("agent_chat").requestId("forced-policy-e2e")
                .conversationId("forced-policy-conversation").tenantId("tenant-e2e").userId("user-e2e")
                .allowedTools(List.of(localToolName))
                .attributes(Map.of("requiredToolParameters", Map.of(
                    localToolName, Map.of("financial_data_required", true))))
                .toolInput(modelInputWithoutMarker)
                .build());

            ToolOutput enriched = execution.output();
            assertThat(enriched.isSuccess()).isTrue();
            Map<String, Object> data = (Map<String, Object>) enriched.getData();
            assertThat(data).containsEntry("financialDataRequired", true)
                .containsEntry("financialDataSatisfied", true)
                .containsEntry("financialDatasetCount", 1)
                .containsEntry("financialObservationCount", 1);
            assertThat((List<Map<String, Object>>) data.get("financialData")).singleElement()
                .satisfies(result -> assertThat(result).containsEntry("dataset", runtimeDataset));
            assertThat(execution.audit())
                .containsEntry("runtimeRequiredToolParametersApplied",
                    List.of("financial_data_required"));
            verify(catalog).search(query, 4);
            verify(store).query(runtimeDataset, Map.of(), null, null, 20, "auto");
        } finally {
            runtime.shutdown();
        }
    }
}
