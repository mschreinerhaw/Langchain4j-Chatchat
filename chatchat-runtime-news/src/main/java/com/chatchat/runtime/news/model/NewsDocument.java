package com.chatchat.runtime.news.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Model-ready news content. Raw HTML and site chrome are deliberately excluded. */
public record NewsDocument(
    String documentId,
    Long sourceId,
    String sourceName,
    NewsSourceType sourceType,
    String title,
    String content,
    String summary,
    String author,
    String sourceUrl,
    Instant publishTime,
    Instant collectTime,
    String language,
    List<String> categories,
    List<String> tags,
    String contentHash,
    NewsAnalysisStatus analysisStatus,
    Map<String, Object> metadata
) {
}
