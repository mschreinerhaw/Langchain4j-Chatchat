package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.springframework.stereotype.Component;

@Component
public class NewsSearchToolExecutor implements NewsToolExecutor {
    private final NewsDocumentStore store;
    private final NewsRuntimeProperties properties;

    public NewsSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override public ToolOutput execute(ToolInput input) {
        if (!properties.getOpenSearch().isEnabled()) return NewsToolSupport.unavailable(NewsToolNames.NEWS_SEARCH);
        try {
            String query = input.getParameterAsString("query", "").trim();
            if (query.isBlank()) return ToolOutput.failure("query parameter is required");
            var documents = store.search(new NewsSearchQuery(query,
                NewsToolSupport.longs(input.getParameter("sourceIds")),
                NewsToolSupport.instant(input.getParameterAsString("startTime", null)),
                NewsToolSupport.instant(input.getParameterAsString("endTime", null)),
                NewsToolSupport.strings(input.getParameter("categories")),
                NewsToolSupport.boundedInt(input.getParameterAsNumber("size"), 10, 1, 50)));
            var items = documents.stream().map(NewsToolSupport::evidenceItem).toList();
            return ToolOutput.success(java.util.Map.of("count", items.size(), "items", items,
                    "reference_urls", NewsToolSupport.evidenceUrls(items)),
                "Use these news records as evidence for the requested summary or analysis.");
        } catch (Exception ex) { return ToolOutput.failure(ex); }
    }
}
