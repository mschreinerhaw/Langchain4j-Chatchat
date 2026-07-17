package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Component
public class NewsLatestToolExecutor implements NewsToolExecutor {
    private final NewsDocumentStore store;
    private final NewsRuntimeProperties properties;

    public NewsLatestToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override public ToolOutput execute(ToolInput input) {
        if (!properties.getOpenSearch().isEnabled()) return NewsToolSupport.unavailable(NewsToolNames.NEWS_LATEST);
        try {
            int hours = NewsToolSupport.boundedInt(input.getParameterAsNumber("hours"), 24, 1, 720);
            int size = NewsToolSupport.boundedInt(input.getParameterAsNumber("size"), 10, 1, 50);
            Instant end = Instant.now();
            var documents = store.search(new NewsSearchQuery(null,
                NewsToolSupport.longs(input.getParameter("sourceIds")), end.minus(hours, ChronoUnit.HOURS), end, List.of(), size));
            var items = documents.stream().map(NewsToolSupport::evidenceItem).toList();
            return ToolOutput.success(Map.of("count", items.size(), "items", items,
                "reference_urls", NewsToolSupport.evidenceUrls(items)), "Latest news retrieved");
        } catch (Exception ex) { return ToolOutput.failure(ex); }
    }
}
