package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.search.TencentWebSearchClient;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WebSearchToolExecutor implements NewsToolExecutor {
    private final NewsDocumentStore store;
    private final NewsRuntimeProperties properties;
    private final TencentWebSearchClient externalSearch;

    public WebSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties,
                                 TencentWebSearchClient externalSearch) {
        this.store = store;
        this.properties = properties;
        this.externalSearch = externalSearch;
    }

    @Override
    public ToolOutput execute(ToolInput input) {
        String query = input.getParameterAsString("query", "").trim();
        if (query.isBlank()) return ToolOutput.failure("query parameter is required");
        boolean localEnabled = properties.getOpenSearch().isEnabled();
        boolean externalEnabled = externalSearch.enabled();
        if (!localEnabled && !externalEnabled) return NewsToolSupport.unavailable(NewsToolNames.WEB_SEARCH);

        int size = NewsToolSupport.boundedInt(input.getParameterAsNumber("num_results"), 10, 1, 50);
        List<Map<String, Object>> local = new ArrayList<>();
        List<Map<String, Object>> external = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        TencentWebSearchClient.SearchResponse externalResponse = null;
        boolean localSucceeded = false;
        boolean externalSucceeded = false;
        if (localEnabled) {
            try {
                List<NewsDocument> documents = store.search(
                    new NewsSearchQuery(query, List.of(), null, null, List.of(), size));
                documents.forEach(document -> local.add(localItem(document)));
                localSucceeded = true;
            } catch (Exception ex) {
                warnings.add("news_index: " + safe(ex));
            }
        }
        if (externalEnabled) {
            try {
                externalResponse = externalSearch.search(query, size);
                externalResponse.pages().forEach(page -> external.add(externalItem(page)));
                externalSucceeded = true;
            } catch (Exception ex) {
                warnings.add("tencent_wsa: " + safe(ex));
            }
        }
        if (!localSucceeded && !externalSucceeded) {
            return ToolOutput.failure("Web retrieval unavailable: " + String.join("; ", warnings));
        }

        List<Map<String, Object>> results = fuse(local, external, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("provider", "chatchat-runtime-news");
        data.put("mode", localEnabled && externalEnabled ? "hybrid_news_and_web" :
            (externalEnabled ? "external_web_search" : "news_index"));
        data.put("count", results.size());
        data.put("newsIndexCount", local.size());
        data.put("externalWebCount", external.size());
        data.put("results", results);
        data.put("reference_urls", NewsToolSupport.evidenceUrls(results));
        if (externalResponse != null) {
            data.put("externalProvider", "tencent-wsa");
            data.put("externalRequestId", externalResponse.requestId());
            data.put("externalVersion", externalResponse.version());
        }
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        return ToolOutput.success(data, "Internal news and external web retrieval completed");
    }

    private Map<String, Object> localItem(NewsDocument document) {
        Map<String, Object> item = NewsToolSupport.evidenceItem(document);
        item.put("resultType", "news");
        item.put("retrievalSource", "news_index");
        item.put("snippet", document.summary() == null || document.summary().isBlank()
            ? NewsToolSupport.abbreviate(document.content(), 500) : document.summary());
        return item;
    }

    private Map<String, Object> externalItem(TencentWebSearchClient.SearchPage page) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resultType", "web");
        item.put("documentKind", "external_web_page");
        item.put("retrievalSource", "tencent_wsa");
        item.put("title", page.title());
        item.put("url", page.url());
        item.put("sourceUrl", page.url());
        item.put("snippet", page.snippet());
        item.put("summary", page.snippet());
        item.put("sourceName", page.site());
        item.put("publishTime", page.date());
        item.put("relevanceScore", page.score());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("title", page.title());
        evidence.put("url", page.url());
        evidence.put("sourceName", page.site());
        evidence.put("publishTime", page.date());
        item.put("evidence", evidence);
        return item;
    }

    /** Reciprocal-rank fusion keeps both corpora useful and boosts duplicate corroboration. */
    private List<Map<String, Object>> fuse(List<Map<String, Object>> local,
                                           List<Map<String, Object>> external, int limit) {
        Map<String, RankedItem> fused = new LinkedHashMap<>();
        addRanked(fused, local);
        addRanked(fused, external);
        return fused.values().stream()
            .sorted(Comparator.comparingDouble(RankedItem::score).reversed())
            .limit(limit).map(RankedItem::item).toList();
    }

    private void addRanked(Map<String, RankedItem> fused, List<Map<String, Object>> items) {
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            String key = dedupeKey(item);
            double contribution = 1D / (60D + index + 1D);
            RankedItem existing = fused.get(key);
            if (existing == null) {
                fused.put(key, new RankedItem(item, contribution));
            } else {
                Map<String, Object> merged = new LinkedHashMap<>(existing.item());
                merged.put("corroborated", true);
                merged.put("retrievalSources", List.of(
                    String.valueOf(existing.item().getOrDefault("retrievalSource", "unknown")),
                    String.valueOf(item.getOrDefault("retrievalSource", "unknown"))));
                fused.put(key, new RankedItem(merged, existing.score() + contribution));
            }
        }
    }

    private String dedupeKey(Map<String, Object> item) {
        String url = String.valueOf(item.getOrDefault("url", "")).trim().toLowerCase(Locale.ROOT);
        if (!url.isBlank()) return "url:" + url.replaceFirst("[/#]+$", "");
        String title = String.valueOf(item.getOrDefault("title", "")).trim().toLowerCase(Locale.ROOT);
        return "title:" + title.replaceAll("\\s+", "");
    }

    private String safe(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record RankedItem(Map<String, Object> item, double score) { }
}
