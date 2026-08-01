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
            6,
            null);

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
}
