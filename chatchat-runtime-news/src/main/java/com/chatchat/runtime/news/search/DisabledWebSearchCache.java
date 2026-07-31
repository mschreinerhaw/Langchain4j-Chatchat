package com.chatchat.runtime.news.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.open-search", name = "enabled",
    havingValue = "false", matchIfMissing = true)
public class DisabledWebSearchCache implements WebSearchCache {
    @Override
    public Optional<CachedSearch> findHighlyRelated(String query) {
        return Optional.empty();
    }

    @Override
    public void put(String query, TencentWebSearchClient.SearchResponse response) { }

    @Override
    public List<String> deleteExpiredIndices() {
        return List.of();
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
