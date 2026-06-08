package com.chatchat.knowledgebase.search;

import java.util.List;

public record SearchPage(
    String keyword,
    List<String> queryTokens,
    List<SearchResult> results,
    int total,
    int limit,
    int page,
    int pageSize,
    int totalPages,
    boolean hasMore,
    long tookMs,
    int documentCount,
    String message
) {
}
