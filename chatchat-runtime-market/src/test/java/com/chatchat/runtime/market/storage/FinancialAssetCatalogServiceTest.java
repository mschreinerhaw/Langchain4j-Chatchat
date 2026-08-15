package com.chatchat.runtime.market.storage;

import com.chatchat.runtime.market.config.MarketModuleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialAssetCatalogServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToMcpDatabaseWhenCentralOpenSearchIsDisabled() {
        FinancialDataStore store = mock(FinancialDataStore.class);
        MarketAssetCatalogIndex index = mock(MarketAssetCatalogIndex.class);
        ObjectProvider<MarketAssetCatalogIndex> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(index);
        when(index.available()).thenReturn(false);
        when(store.searchCatalog("", 50)).thenReturn(List.of(Map.of(
            "dataset_code", "etf_scale_daily", "asset_name", "ETF规模每日数据")));
        FinancialAssetCatalogService service = new FinancialAssetCatalogService(store, provider,
            new MarketModuleProperties());

        assertThat(service.search("ETF规模", 10)).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("dataset_code", "etf_scale_daily"));
        verify(store).searchCatalog("", 50);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrievesABroadIndexCandidatePoolBeforeSemanticReranking() {
        FinancialDataStore store = mock(FinancialDataStore.class);
        MarketAssetCatalogIndex index = mock(MarketAssetCatalogIndex.class);
        ObjectProvider<MarketAssetCatalogIndex> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(index);
        when(index.available()).thenReturn(true);
        when(index.search("financial-data-asset", "指数成交量", 24)).thenReturn(List.of(
            Map.of("dataset_code", "noise", "asset_name", "债券统计"),
            Map.of("dataset_code", "quotes", "asset_name", "指数行情", "business_tags_json", "成交量")));
        FinancialAssetCatalogService service = new FinancialAssetCatalogService(store, provider,
            new MarketModuleProperties());

        assertThat(service.search("指数成交量", 3)).extracting(item -> item.get("dataset_code"))
            .containsExactly("quotes");
        verify(index).search("financial-data-asset", "指数成交量", 24);
    }
}
