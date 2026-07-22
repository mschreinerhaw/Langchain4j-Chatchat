package com.chatchat.runtime.market.storage;

import com.chatchat.runtime.market.config.MarketModuleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        when(store.searchCatalog("ETF规模", 10)).thenReturn(List.of(Map.of("dataset_code", "etf_scale_daily")));
        FinancialAssetCatalogService service = new FinancialAssetCatalogService(store, provider,
            new MarketModuleProperties());

        assertThat(service.search("ETF规模", 10)).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("dataset_code", "etf_scale_daily"));
    }
}
