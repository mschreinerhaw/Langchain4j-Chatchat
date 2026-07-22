package com.chatchat.runtime.market.storage;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FinancialAssetCatalogSynchronizerTest {
    @Test
    void synchronizesExistingCatalogAfterApplicationStartup() {
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        FinancialAssetCatalogSynchronizer synchronizer = new FinancialAssetCatalogSynchronizer(catalog, Runnable::run);

        synchronizer.onApplicationReady();

        verify(catalog).synchronizeExistingCatalog();
    }
}
