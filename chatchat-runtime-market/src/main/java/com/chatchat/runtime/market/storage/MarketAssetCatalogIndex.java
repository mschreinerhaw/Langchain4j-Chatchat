package com.chatchat.runtime.market.storage;

import java.util.List;
import java.util.Map;

/** MCP-owned catalog index gateway. The market library never owns OpenSearch connection settings. */
public interface MarketAssetCatalogIndex {
    boolean available();
    void ensureIndex(String indexName);
    void index(String indexName, String datasetCode, Map<String, Object> catalog);
    List<Map<String, Object>> search(String indexName, String query, int limit);
}
