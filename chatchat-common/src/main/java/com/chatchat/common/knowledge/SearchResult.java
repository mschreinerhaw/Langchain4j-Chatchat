package com.chatchat.common.knowledge;

import java.util.List;
import java.util.Map;

/** Canonical result port shared by template, document, asset and metadata search. */
public interface SearchResult<T extends KnowledgeDocument> {
    String schemaVersion();
    String query();
    SearchStatus status();
    List<SearchHit<T>> hits();
    long totalHits();
    int limit();
    boolean truncated();
    Map<String, Object> metadata();
    long completedAt();
}
