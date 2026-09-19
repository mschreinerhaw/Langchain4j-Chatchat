package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsSourceType;

import java.util.Set;

/** Stable metadata used to discover, select and diagnose a collector implementation. */
public record NewsCollectorDescriptor(String collectorId, Set<NewsSourceType> sourceTypes, int priority) {
    public NewsCollectorDescriptor {
        if (collectorId == null || collectorId.isBlank()) {
            throw new IllegalArgumentException("collectorId is required");
        }
        sourceTypes = sourceTypes == null ? Set.of() : Set.copyOf(sourceTypes);
    }
}
