package com.chatchat.runtime.market.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Runs the MCP-owned hot-detail cleanup only after weekly snapshots have been persisted. */
@Service
public class FinancialDataRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(FinancialDataRetentionScheduler.class);
    private final FinancialDataStore store;
    private final FinancialAssetCatalogService catalog;

    public FinancialDataRetentionScheduler(FinancialDataStore store, FinancialAssetCatalogService catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @Scheduled(cron = "${chatchat.mcp.market.retention.cron:0 30 2 * * SUN}",
        zone = "${chatchat.mcp.market.retention.zone-id:Asia/Shanghai}")
    public void archiveWeeklyAndCleanupHotData() {
        FinancialDataStore.RetentionRunResult result = store.archiveAndCleanup();
        catalog.synchronizeExistingCatalog();
        log.info("financial_data_retention_completed runDate={} archivedRows={} deletedHotRows={} prunedArchiveRows={} datasets={}",
            result.runDate(), result.archivedRows(), result.deletedHotRows(), result.prunedArchiveRows(),
            result.datasets().size());
    }
}
