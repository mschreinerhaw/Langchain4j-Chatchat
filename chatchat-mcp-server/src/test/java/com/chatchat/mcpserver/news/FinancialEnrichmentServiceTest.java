package com.chatchat.mcpserver.news;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialEnrichmentServiceTest {

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
    void ordinarySearchDiscoversAssetsButNeverReadsFinancialRowsImplicitly() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(store.assetSearchQuery("company quote", 10)).thenReturn("company quote");
        when(catalog.search("company quote", 6)).thenReturn(java.util.List.of(
            Map.of("dataset_code", "dynamic_dataset")));
        FinancialEnrichmentService service = new FinancialEnrichmentService(catalog, store);

        FinancialEnrichmentService.EnrichmentResult result = service.enrich(
            "company quote", ToolInput.builder().build(), 6);

        assertThat(result.skippedReason()).isEqualTo("explicit_dataset_required");
        assertThat(result.assets()).singleElement().satisfies(asset ->
                assertThat(asset).containsEntry("dataset_code", "dynamic_dataset"));
        assertThat(result.financialData()).isEmpty();
        verify(store, never()).resolveEntityFilters(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        verify(store, never()).query(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }
}
