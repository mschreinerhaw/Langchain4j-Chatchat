package com.chatchat.runtime.news.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Declares News tool implementations; registration and publication belong to the MCP registry. */
@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NewsMcpToolProvider {
    private final Map<String, NewsToolExecutor> executors;

    public NewsMcpToolProvider(WebSearchToolExecutor webSearch, NewsSearchToolExecutor newsSearch,
                               NewsLatestToolExecutor newsLatest, NewsSourceStatusToolExecutor sourceStatus) {
        this.executors = Map.of(
            NewsToolNames.WEB_SEARCH, webSearch,
            NewsToolNames.NEWS_SEARCH, newsSearch,
            NewsToolNames.NEWS_LATEST, newsLatest,
            NewsToolNames.NEWS_SOURCE_STATUS, sourceStatus
        );
    }

    public Optional<NewsToolExecutor> findExecutor(String toolName) {
        return Optional.ofNullable(executors.get(toolName));
    }
}
