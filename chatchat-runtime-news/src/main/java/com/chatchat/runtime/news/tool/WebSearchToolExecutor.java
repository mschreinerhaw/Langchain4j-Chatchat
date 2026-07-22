package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WebSearchToolExecutor implements NewsToolExecutor {
    private final NewsDocumentStore store;
    private final NewsRuntimeProperties properties;

    public WebSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public ToolOutput execute(ToolInput input) {
        if (!properties.getOpenSearch().isEnabled()) return NewsToolSupport.unavailable(NewsToolNames.WEB_SEARCH);
        try {
            String query = input.getParameterAsString("query", "").trim();
            if (query.isBlank()) return ToolOutput.failure("query parameter is required");
            int size = NewsToolSupport.boundedInt(input.getParameterAsNumber("num_results"), 10, 1, 50);
            List<NewsDocument> documents = store.search(new NewsSearchQuery(query, List.of(), null, null, List.of(), size));
            List<Map<String, Object>> results = documents.stream().map(document -> {
                Map<String, Object> item = NewsToolSupport.evidenceItem(document);
                item.put("resultType", "news");
                item.put("snippet", document.summary() == null || document.summary().isBlank()
                    ? NewsToolSupport.abbreviate(document.content(), 500) : document.summary());
                return item;
            }).toList();
            return ToolOutput.success(Map.of("query", query, "provider", "chatchat-runtime-news",
                "mode", "news_index", "count", results.size(), "results", results,
                "reference_urls", NewsToolSupport.evidenceUrls(results)), "News index search completed");
        } catch (Exception ex) {
            return ToolOutput.failure(ex);
        }
    }
}
