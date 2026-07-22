package com.chatchat.runtime.market.storage;

import com.chatchat.runtime.market.config.MarketModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Maintains business descriptions while delegating index connectivity to MCP Server. */
@Service
public class FinancialAssetCatalogService {
    private static final Logger log = LoggerFactory.getLogger(FinancialAssetCatalogService.class);
    private final FinancialDataStore store;
    private final MarketAssetCatalogIndex index;
    private final String indexName;
    private final Map<String, Long> lastIndexedAt = new ConcurrentHashMap<>();

    public FinancialAssetCatalogService(FinancialDataStore store,
                                        ObjectProvider<MarketAssetCatalogIndex> indexProvider,
                                        MarketModuleProperties properties) {
        this.store = store;
        this.index = indexProvider.getIfAvailable();
        this.indexName = safeIndex(properties.getCatalogIndexName());
    }

    public void index(FinancialDatasetDefinition definition) {
        if (index == null || !index.available() || definition == null) return;
        long now = System.currentTimeMillis();
        Long previous = lastIndexedAt.putIfAbsent(definition.code(), now);
        if (previous != null && now - previous < 60_000) return;
        lastIndexedAt.put(definition.code(), now);
        try {
            index.ensureIndex(indexName);
            indexCatalog(definition.code());
        } catch (Exception ex) {
            log.warn("financial_asset_catalog_index_failed dataset={} error={}", definition.code(), ex.getMessage());
        }
    }

    public int synchronizeExistingCatalog() {
        if (index == null || !index.available()) return 0;
        int indexed = 0;
        try {
            index.ensureIndex(indexName);
            for (String datasetCode : store.catalogCodes()) {
                if (indexCatalog(datasetCode)) indexed++;
            }
            log.info("financial_asset_catalog_synchronized index={} documents={}", indexName, indexed);
        } catch (Exception ex) {
            log.warn("financial_asset_catalog_synchronization_failed index={} indexed={} error={}",
                indexName, indexed, ex.getMessage());
        }
        return indexed;
    }

    public List<Map<String, Object>> search(String query, int limit) {
        int bounded = Math.max(1, Math.min(limit, 50));
        if (index == null || !index.available() || query == null || query.isBlank()) {
            return store.searchCatalog(query, bounded);
        }
        try {
            return index.search(indexName, query, bounded);
        } catch (Exception ex) {
            log.warn("financial_asset_catalog_search_fallback query={} error={}", query, ex.getMessage());
            return store.searchCatalog(query, bounded);
        }
    }

    private boolean indexCatalog(String datasetCode) {
        Map<String, Object> catalog = store.catalog(datasetCode);
        if (catalog.isEmpty()) return false;
        index.index(indexName, datasetCode, catalog);
        lastIndexedAt.put(datasetCode, System.currentTimeMillis());
        return true;
    }

    private String safeIndex(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
        if (normalized.isBlank()) throw new IllegalArgumentException("financial catalog index name is empty");
        return normalized;
    }
}
