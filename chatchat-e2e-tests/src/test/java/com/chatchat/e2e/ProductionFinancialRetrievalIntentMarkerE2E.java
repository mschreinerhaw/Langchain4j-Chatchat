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
import com.chatchat.mcpserver.news.NewsRuntimeClient;
import com.chatchat.mcpserver.news.NewsSearchService;
import com.chatchat.mcpserver.news.RemoteNewsMcpToolProvider;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Release regression for explicit, non-hardcoded financial retrieval behind unified web_search. */
class ProductionFinancialRetrievalIntentMarkerE2E {

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
        when(catalog.search(query, 6)).thenReturn(List.of(Map.of(
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
            .id(localToolName).title("Runtime registered web search").categories(List.of("mcp")).build());
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
                .attributes(Map.of("forceStructuredFinancialData", true))
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
                .containsEntry("financialDataPolicy", "FORCED")
                .containsEntry("financialDataModelRequired", false)
                .containsEntry("financialDataEffectiveRequired", true);
            verify(store).query(runtimeDataset, Map.of(), null, null, 20, "auto");
        } finally {
            runtime.shutdown();
        }
    }
}
