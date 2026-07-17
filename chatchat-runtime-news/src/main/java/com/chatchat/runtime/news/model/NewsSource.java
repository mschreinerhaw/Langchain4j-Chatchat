package com.chatchat.runtime.news.model;

import java.util.Map;

public record NewsSource(
    Long id,
    String code,
    String name,
    NewsSourceType sourceType,
    String entryUrl,
    String allowedDomain,
    Map<String, String> selectors,
    Map<String, Object> configuration,
    boolean enabled
) {
}
