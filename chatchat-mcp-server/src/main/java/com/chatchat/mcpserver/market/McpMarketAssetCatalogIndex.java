package com.chatchat.mcpserver.market;

import com.chatchat.mcpserver.search.OpenSearchMcpSearchService;
import com.chatchat.runtime.market.storage.MarketAssetCatalogIndex;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class McpMarketAssetCatalogIndex implements MarketAssetCatalogIndex {
    private final OpenSearchMcpSearchService search;

    public McpMarketAssetCatalogIndex(OpenSearchMcpSearchService search) {
        this.search = search;
    }

    @Override public boolean available() {
        return search.enabled();
    }

    @Override public void ensureIndex(String indexName) {
        search.ensureMarketCatalogIndex(indexName);
    }

    @Override public void index(String indexName, String datasetCode, Map<String, Object> catalog) {
        search.indexMarketCatalog(indexName, datasetCode, catalog);
    }

    @Override public List<Map<String, Object>> search(String indexName, String query, int limit) {
        return search.searchMarketCatalog(indexName, query, limit);
    }
}
