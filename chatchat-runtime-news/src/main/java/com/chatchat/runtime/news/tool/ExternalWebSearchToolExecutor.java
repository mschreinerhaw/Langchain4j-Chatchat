package com.chatchat.runtime.news.tool;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.search.ExternalSearchQueryResolver;
import com.chatchat.runtime.news.search.TencentWebSearchClient;
import com.chatchat.runtime.news.search.WebSearchCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal-only external retrieval tool. It is intentionally not registered by
 * {@link NewsMcpToolProvider}; callers can reach it only through the public web_search bridge.
 */
@Component
public class ExternalWebSearchToolExecutor implements NewsToolExecutor {
    public static final String INTERNAL_TOOL_NAME = "external_web_search";
    private static final Logger log = LoggerFactory.getLogger(ExternalWebSearchToolExecutor.class);

    private final NewsRuntimeProperties properties;
    private final TencentWebSearchClient externalSearch;
    private final WebSearchCache cache;

    public ExternalWebSearchToolExecutor(NewsRuntimeProperties properties,
                                         TencentWebSearchClient externalSearch,
                                         WebSearchCache cache) {
        this.properties = properties;
        this.externalSearch = externalSearch;
        this.cache = cache;
    }

    boolean available() {
        return externalSearch.enabled() || cache.enabled();
    }

    @Override
    public ToolOutput execute(ToolInput input) {
        CancellationSupport.throwIfCancelled(INTERNAL_TOOL_NAME);
        String originalQuery = input.getParameterAsString("query", "").trim();
        ExternalSearchQueryResolver.ResolvedQuery resolvedQuery = ExternalSearchQueryResolver.resolve(input);
        String query = resolvedQuery.query();
        if (query.isBlank()) {
            return ToolOutput.failure("Analyzed queryTerms, keywords, or intent is required for external web retrieval; "
                + "the original user question is not sent to the external provider");
        }
        if (!available()) return NewsToolSupport.unavailable(INTERNAL_TOOL_NAME);

        int size = NewsToolSupport.boundedInt(input.getParameterAsNumber("num_results"), 10, 1, 50);
        boolean forceExternal = properties.getWebSearch().getCache().isForceExternal();
        List<String> warnings = new ArrayList<>();
        TencentWebSearchClient.SearchResponse response = null;
        WebSearchCache.CachedSearch cached = null;
        if (cache.enabled() && !forceExternal) {
            try {
                cached = cache.findHighlyRelated(query).orElse(null);
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, INTERNAL_TOOL_NAME + " cache read");
                warnings.add("web_search_cache_read: " + safe(ex));
            }
        }
        String retrievalSource;
        if (cached != null) {
            response = cached.response();
            retrievalSource = "tencent_wsa_cache";
            log.info("internalExternalWebSearch cacheHit=true query=\"{}\" cachedQuery=\"{}\" results={}",
                audit(query), audit(cached.originalQuery()), response.pages().size());
        } else if (externalSearch.enabled()) {
            try {
                response = externalSearch.search(query, size);
                retrievalSource = "tencent_wsa";
                if (cache.enabled()) {
                    try {
                        cache.put(query, response);
                    } catch (Exception ex) {
                        CancellationSupport.rethrowIfCancelled(ex, INTERNAL_TOOL_NAME + " cache write");
                        warnings.add("web_search_cache_write: " + safe(ex));
                    }
                }
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, INTERNAL_TOOL_NAME);
                return ToolOutput.failure("External web retrieval unavailable: " + safe(ex));
            }
        } else {
            return ToolOutput.failure("External web retrieval unavailable: provider disabled or credentials missing");
        }

        List<Map<String, Object>> results = response.pages().stream()
            .map(page -> externalItem(page, retrievalSource)).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", originalQuery);
        data.put("externalSearchQuery", query);
        data.put("externalSearchQuerySource", resolvedQuery.source());
        data.put("externalSearchTerms", resolvedQuery.terms());
        data.put("provider", "chatchat-runtime-news");
        data.put("mode", cached == null ? "external_web_search" : "cached_web_search");
        data.put("internalTool", true);
        data.put("userFacing", false);
        data.put("count", results.size());
        data.put("results", results);
        data.put("reference_urls", NewsToolSupport.evidenceUrls(results));
        data.put("webSearchCacheEnabled", cache.enabled());
        data.put("webSearchCacheHit", cached != null);
        data.put("externalProvider", cached == null ? "tencent-wsa" : "tencent-wsa-cache");
        data.put("externalRequestId", response.requestId());
        data.put("externalVersion", response.version());
        if (cached != null) {
            data.put("cachedQuery", cached.originalQuery());
            data.put("cacheSimilarity", cached.similarity());
        }
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        ToolOutput output = ToolOutput.success(data, "Internal external web retrieval completed");
        output.getMetadata().put("internalTool", true);
        output.getMetadata().put("userFacing", false);
        return output;
    }

    private Map<String, Object> externalItem(TencentWebSearchClient.SearchPage page, String source) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resultType", "web");
        item.put("documentKind", "external_web_page");
        item.put("retrievalSource", source);
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

    private String audit(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private String safe(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
