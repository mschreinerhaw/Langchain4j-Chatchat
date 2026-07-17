package com.chatchat.runtime.news.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RawNewsItem(
    NewsSource source,
    String title,
    String content,
    String summary,
    String author,
    String sourceUrl,
    Instant publishTime,
    String language,
    List<String> categories,
    List<String> tags,
    Map<String, Object> metadata
) {
}
