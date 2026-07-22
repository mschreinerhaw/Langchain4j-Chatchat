package com.chatchat.runtime.market.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/** Rebuilds the OpenSearch asset catalog from the authoritative database after startup. */
@Component
class FinancialAssetCatalogSynchronizer {
    private final FinancialAssetCatalogService catalog;
    private final Executor executor;

    FinancialAssetCatalogSynchronizer(FinancialAssetCatalogService catalog,
                                      @Qualifier("marketModuleExecutor") Executor executor) {
        this.catalog = catalog;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        executor.execute(catalog::synchronizeExistingCatalog);
    }
}
