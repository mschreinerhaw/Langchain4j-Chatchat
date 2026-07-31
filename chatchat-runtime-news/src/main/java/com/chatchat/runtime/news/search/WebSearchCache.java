package com.chatchat.runtime.news.search;

import java.util.List;
import java.util.Optional;

public interface WebSearchCache {
    Optional<CachedSearch> findHighlyRelated(String query) throws Exception;

    void put(String query, TencentWebSearchClient.SearchResponse response) throws Exception;

    List<String> deleteExpiredIndices() throws Exception;

    boolean enabled();

    record CachedSearch(String originalQuery, double similarity,
                        TencentWebSearchClient.SearchResponse response) { }
}
