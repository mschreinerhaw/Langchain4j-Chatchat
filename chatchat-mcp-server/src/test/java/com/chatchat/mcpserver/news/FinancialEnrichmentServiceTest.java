package com.chatchat.mcpserver.news;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class FinancialEnrichmentServiceTest {

    @Test
    void lowConfidenceCatalogSearchExpandsAndReranksUsingMetadataWithoutBusinessRules() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String query = "A股午间主要指数成交量板块分析";
        when(store.assetSearchQuery(query, 10)).thenReturn(query);
        Map<String, Object> unrelated = Map.of(
            "dataset_code", "unrelated_dataset", "asset_name", "无关数据集");
        Map<String, Object> relevant = Map.of(
            "dataset_code", "market_quote_daily", "asset_name", "A股及主要指数行情");
        when(catalog.search(query, 6)).thenReturn(List.of(unrelated));
        when(catalog.search(query, 24)).thenReturn(List.of(unrelated, relevant));
        when(store.resolveEntityFilters("market_quote_daily", query, 5)).thenReturn(List.of());
        when(store.query("market_quote_daily", Map.of(), null, null, 20, "auto"))
            .thenReturn(Map.of("rows", List.of(Map.of("quote_name", "上证指数", "close", 3200))));
        FinancialEnrichmentService service = new FinancialEnrichmentService(catalog, store);

        FinancialEnrichmentService.EnrichmentResult result = service.enrich(
            query, ToolInput.builder().parameters(Map.of("financial_dataset_limit", 1)).build(), 6);

        assertThat(result.assets()).extracting(asset -> asset.get("dataset_code"))
            .startsWith("market_quote_daily");
        assertThat(result.financialData()).singleElement()
            .satisfies(data -> assertThat(data).containsEntry("dataset", "market_quote_daily"));
        verify(store, never()).query(eq("unrelated_dataset"), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void finalSummaryPolicySkipsEveryFinancialDependency() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        FinancialEnrichmentService service = new FinancialEnrichmentService(catalog, store);

        FinancialEnrichmentService.EnrichmentResult result = service.enrich(
            "latest announcements",
            ToolInput.builder().context(Map.of(
                "internalPurpose", FinancialEnrichmentService.FINAL_SUMMARY_PURPOSE)).build(),
            6);

        assertThat(result.skippedReason()).isEqualTo("runtime_context_disabled");
        assertThat(result.assets()).isEmpty();
        assertThat(result.financialData()).isEmpty();
        verify(catalog, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(store, never()).assetSearchQuery(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(store, never()).query(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ordinarySearchDynamicallyReadsMatchedLocalFinancialRows() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(store.assetSearchQuery("company quote", 10)).thenReturn("company quote");
        when(catalog.search("company quote", 6)).thenReturn(java.util.List.of(
            Map.of("dataset_code", "dynamic_dataset")));
        when(store.resolveEntityFilters("dynamic_dataset", "company quote", 5)).thenReturn(List.of());
        when(store.query("dynamic_dataset", Map.of(), null, null, 20, "auto"))
            .thenReturn(Map.of("rows", List.of(Map.of("symbol", "000001", "close", 10.98))));
        FinancialEnrichmentService service = new FinancialEnrichmentService(catalog, store);

        FinancialEnrichmentService.EnrichmentResult result = service.enrich(
            "company quote", ToolInput.builder().build(), 6);

        assertThat(result.skippedReason()).isNull();
        assertThat(result.assets()).singleElement().satisfies(asset ->
                assertThat(asset).containsEntry("dataset_code", "dynamic_dataset"));
        assertThat(result.financialData()).singleElement().satisfies(data ->
            assertThat(data).containsEntry("dataset", "dynamic_dataset")
                .containsEntry("retrievalSource", "governed_financial_store"));
        verify(store).query("dynamic_dataset", Map.of(), null, null, 20, "auto");
    }

    @Test
    void explicitRetrievalIntentDynamicallySelectsCatalogResultAndReadsBoundedRows() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(store.assetSearchQuery("opening analysis", 10)).thenReturn("opening analysis");
        when(catalog.search("opening analysis", 6)).thenReturn(java.util.List.of(
            Map.of("dataset_code", "runtime_registered_dataset")));
        when(store.resolveEntityFilters("runtime_registered_dataset", "opening analysis", 5))
            .thenReturn(java.util.List.of());
        when(store.query("runtime_registered_dataset", Map.of(), null, null, 20, "auto"))
            .thenReturn(Map.of("rows", java.util.List.of(Map.of("metric", "breadth", "value", 123))));
        FinancialEnrichmentService service = new FinancialEnrichmentService(catalog, store);

        FinancialEnrichmentService.EnrichmentResult result = service.enrich(
            "opening analysis",
            ToolInput.builder().parameters(Map.of("financial_data_required", true)).context(Map.of(
                "internalPurpose", FinancialEnrichmentService.FINAL_SUMMARY_PURPOSE)).build(),
            6);

        assertThat(result.skippedReason()).isNull();
        assertThat(result.financialData()).singleElement().satisfies(data -> {
            assertThat(data).containsEntry("dataset", "runtime_registered_dataset")
                .containsEntry("retrievalSource", "governed_financial_store");
        });
        verify(store).query("runtime_registered_dataset", Map.of(), null, null, 20, "auto");
    }

    @Test
    void forcedRetrievalUsesFullUserIntentAndSkipsEmptyCandidatesWithinBoundedPool() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        String stepQuery = "company announcements";
        String fullIntent = "A share opening analysis with market breadth and fund flow";
        when(store.assetSearchQuery(fullIntent, 10)).thenReturn(fullIntent);
        when(catalog.search(fullIntent, 6)).thenReturn(List.of(
            Map.of("dataset_code", "empty_candidate"),
            Map.of("dataset_code", "runtime_market_dataset"),
            Map.of("dataset_code", "unused_candidate")));
        when(store.resolveEntityFilters(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(stepQuery), org.mockito.ArgumentMatchers.eq(5)))
            .thenReturn(List.of());
        when(store.query("empty_candidate", Map.of(), null, null, 20, "auto"))
            .thenReturn(Map.of("rows", List.of(), "count", 0));
        when(store.query("runtime_market_dataset", Map.of(), null, null, 20, "auto"))
            .thenReturn(Map.of("rows", List.of(Map.of("metric", "breadth", "value", 123)), "count", 1));
        FinancialEnrichmentService service = new FinancialEnrichmentService(catalog, store);

        FinancialEnrichmentService.EnrichmentResult result = service.enrich(
            stepQuery,
            ToolInput.builder().parameters(Map.of(
                "financial_data_required", true,
                "financial_dataset_limit", 1,
                "financial_row_limit", 20)).context(Map.of(
                    "financialIntentQuery", fullIntent)).build(),
            6);

        assertThat(result.assetQuery()).isEqualTo(fullIntent);
        assertThat(result.financialData()).singleElement().satisfies(data ->
            assertThat(data).containsEntry("dataset", "runtime_market_dataset"));
        assertThat(result.warnings()).anyMatch(value -> value.contains("no matching observations"));
        verify(store, never()).query("unused_candidate", Map.of(), null, null, 20, "auto");
    }

    @Test
    void propagatesTenantFromMcpArgumentsIntoFinancialCacheScope() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        FinancialQueryCacheService cache = mock(FinancialQueryCacheService.class);
        when(cache.getOrLoad(eq("fund_scale"), eq(Map.of()), eq(null), eq(null), eq(50), eq("auto"),
            eq("tenant-9001"), any())).thenReturn(Map.of("rows", List.of(Map.of("fund", "ETF"))));
        FinancialEnrichmentService service = new FinancialEnrichmentService(catalog, store, cache);
        ToolInput input = ToolInput.builder().parameters(Map.of(
            "dataset", "fund_scale",
            "mcpContext", Map.of("tenant", Map.of("tenantId", "tenant-9001")))).build();

        Map<String, Object> result = service.queryDataset("fund_scale", input);

        assertThat(result).containsKey("rows");
        verify(cache).getOrLoad(eq("fund_scale"), eq(Map.of()), eq(null), eq(null), eq(50), eq("auto"),
            eq("tenant-9001"), any());
    }
}
