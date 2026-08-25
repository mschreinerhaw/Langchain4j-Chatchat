package com.chatchat.common.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Immutable default implementation of the SearchResult protocol. */
public record StandardSearchResult<T extends KnowledgeDocument>(
    String schemaVersion,
    String query,
    SearchStatus status,
    List<SearchHit<T>> hits,
    long totalHits,
    int limit,
    boolean truncated,
    Map<String, Object> metadata,
    long completedAt
) implements SearchResult<T> {
    public static final String SCHEMA_VERSION = "knowledge_search_result.v1";

    public StandardSearchResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        query = query == null ? "" : query.trim();
        hits = hits == null ? List.of() : List.copyOf(hits);
        status = status == null ? (hits.isEmpty() ? SearchStatus.EMPTY : SearchStatus.FOUND) : status;
        totalHits = Math.max(totalHits, hits.size());
        limit = Math.max(0, limit);
        metadata = metadata == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        completedAt = completedAt <= 0 ? System.currentTimeMillis() : completedAt;
    }
}
