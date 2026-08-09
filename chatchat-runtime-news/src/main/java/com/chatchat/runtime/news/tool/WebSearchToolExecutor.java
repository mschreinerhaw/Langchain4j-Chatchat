package com.chatchat.runtime.news.tool;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.search.TencentWebSearchClient;
import com.chatchat.runtime.news.search.WebSearchCache;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WebSearchToolExecutor implements NewsToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebSearchToolExecutor.class);
    private final NewsDocumentStore store;
    private final NewsRuntimeProperties properties;
    private final TencentWebSearchClient externalSearch;
    private final WebSearchCache cache;

    @Autowired
    public WebSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties,
                                 TencentWebSearchClient externalSearch, WebSearchCache cache) {
        this.store = store;
        this.properties = properties;
        this.externalSearch = externalSearch;
        this.cache = cache;
    }

    public WebSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties,
                                 TencentWebSearchClient externalSearch) {
        this(store, properties, externalSearch, new com.chatchat.runtime.news.search.DisabledWebSearchCache());
    }

    @Override
    public ToolOutput execute(ToolInput input) {
        CancellationSupport.throwIfCancelled("web_search");
        String query = input.getParameterAsString("query", "").trim();
        if (query.isBlank()) return ToolOutput.failure("query parameter is required");
        boolean localEnabled = properties.getOpenSearch().isEnabled();
        boolean externalEnabled = externalSearch.enabled();
        boolean cacheEnabled = cache.enabled();
        if (!localEnabled && !externalEnabled && !cacheEnabled) {
            return NewsToolSupport.unavailable(NewsToolNames.WEB_SEARCH);
        }

        int size = NewsToolSupport.boundedInt(input.getParameterAsNumber("num_results"), 10, 1, 50);
        List<Map<String, Object>> local = new ArrayList<>();
        List<Map<String, Object>> external = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        TencentWebSearchClient.SearchResponse externalResponse = null;
        WebSearchCache.CachedSearch cachedSearch = null;
        boolean localSucceeded = false;
        boolean externalSucceeded = false;
        if (localEnabled) {
            try {
                List<NewsDocument> documents = store.search(
                    new NewsSearchQuery(query, List.of(), null, null, List.of(), size));
                documents.forEach(document -> local.add(localItem(document)));
                localSucceeded = true;
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, "web_search local retrieval");
                warnings.add("news_index: " + safe(ex));
            }
        }
        CancellationSupport.throwIfCancelled("web_search");
        boolean forceExternal = properties.getWebSearch().getCache().isForceExternal();
        int requiredLocalResults = Math.min(size,
            Math.max(1, properties.getWebSearch().getMinimumLocalResults()));
        int upstreamLocalEvidenceCount = upstreamLocalEvidenceCount(input);
        int totalLocalEvidenceCount = local.size() + upstreamLocalEvidenceCount;
        boolean localSufficient = (localSucceeded || upstreamLocalEvidenceCount > 0)
            && totalLocalEvidenceCount >= requiredLocalResults;
        boolean externalSupplementRequired = forceExternal || !localSufficient;
        log.info(
            "webSearchRetrievalRoute query=\"{}\" requested={} localEnabled={} tencentWsaEnabled={} "
                + "cacheEnabled={} forceExternal={} localNewsCount={} upstreamLocalEvidenceCount={} "
                + "totalLocalEvidenceCount={} requiredLocalCount={} "
                + "externalSupplementRequired={}",
            auditQuery(query), size, localEnabled, externalEnabled, cacheEnabled, forceExternal,
            local.size(), upstreamLocalEvidenceCount, totalLocalEvidenceCount, requiredLocalResults,
            externalSupplementRequired
        );
        if (externalSupplementRequired && cacheEnabled && !forceExternal) {
            try {
                cachedSearch = cache.findHighlyRelated(query).orElse(null);
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, "web_search cache read");
                warnings.add("web_search_cache_read: " + safe(ex));
                log.warn("webSearchCacheReadFailed query=\"{}\" error={}", auditQuery(query), safe(ex));
            }
        }
        if (cachedSearch != null) {
            externalResponse = cachedSearch.response();
            externalResponse.pages().forEach(page -> external.add(externalItem(page, "tencent_wsa_cache")));
            externalSucceeded = true;
            log.info(
                "tencentWsaHttpCallSkipped query=\"{}\" reason=cache_hit cachedQuery=\"{}\" similarity={} "
                    + "resultCount={}",
                auditQuery(query), auditQuery(cachedSearch.originalQuery()), cachedSearch.similarity(),
                externalResponse.pages().size()
            );
        } else if (externalSupplementRequired && externalEnabled) {
            try {
                CancellationSupport.throwIfCancelled("web_search external retrieval");
                log.info(
                    "准备调用联网检索API provider=tencent-wsa query=\"{}\" requested={} "
                        + "cacheResult={} forceExternal={} requestId={}",
                    auditQuery(query), size, cacheEnabled ? "miss" : "disabled", forceExternal,
                    auditIdentifier(input.getRequestId())
                );
                externalResponse = externalSearch.search(query, size);
                externalResponse.pages().forEach(page -> external.add(externalItem(page, "tencent_wsa")));
                externalSucceeded = true;
                if (cacheEnabled) {
                    try {
                        cache.put(query, externalResponse);
                    } catch (Exception ex) {
                        CancellationSupport.rethrowIfCancelled(ex, "web_search cache write");
                        warnings.add("web_search_cache_write: " + safe(ex));
                        log.warn("webSearchCacheWriteFailed query=\"{}\" error={}", auditQuery(query), safe(ex));
                    }
                }
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, "web_search external retrieval");
                warnings.add("tencent_wsa: " + safe(ex));
                log.warn("tencentWsaRetrievalFailed query=\"{}\" error={}", auditQuery(query), safe(ex));
            }
        } else if (!externalSupplementRequired) {
            log.info(
                "tencentWsaHttpCallSkipped query=\"{}\" reason=local_evidence_sufficient "
                    + "localNewsCount={} upstreamLocalEvidenceCount={} requiredLocalCount={}",
                auditQuery(query), local.size(), upstreamLocalEvidenceCount, requiredLocalResults
            );
        } else {
            log.info(
                "tencentWsaHttpCallSkipped query=\"{}\" reason=provider_disabled_or_credentials_missing",
                auditQuery(query)
            );
        }
        CancellationSupport.throwIfCancelled("web_search");
        if (!localSucceeded && !externalSucceeded) {
            return ToolOutput.failure("Web retrieval unavailable: " + String.join("; ", warnings));
        }

        List<Map<String, Object>> results = fuse(local, external, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("provider", "chatchat-runtime-news");
        data.put("mode", localSucceeded && externalSucceeded ? "hybrid_news_and_web" :
            (externalSucceeded ? (cachedSearch == null ? "external_web_search" : "cached_web_search") : "news_index"));
        data.put("count", results.size());
        data.put("newsIndexCount", local.size());
        data.put("externalWebCount", external.size());
        data.put("webSearchCacheEnabled", cacheEnabled);
        data.put("webSearchCacheHit", cachedSearch != null);
        data.put("forcedExternalSearch", properties.getWebSearch().getCache().isForceExternal());
        data.put("localEvidenceSufficient", localSufficient);
        data.put("upstreamLocalEvidenceCount", upstreamLocalEvidenceCount);
        data.put("totalLocalEvidenceCount", totalLocalEvidenceCount);
        data.put("minimumLocalResults", requiredLocalResults);
        data.put("externalSearchRole", "supplementary_fallback");
        data.put("externalSearchRequired", externalSupplementRequired);
        if (cachedSearch != null) {
            data.put("cachedQuery", cachedSearch.originalQuery());
            data.put("cacheSimilarity", cachedSearch.similarity());
        }
        data.put("results", results);
        data.put("reference_urls", NewsToolSupport.evidenceUrls(results));
        if (externalResponse != null) {
            data.put("externalProvider", cachedSearch == null ? "tencent-wsa" : "tencent-wsa-cache");
            data.put("externalRequestId", externalResponse.requestId());
            data.put("externalVersion", externalResponse.version());
        }
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        return ToolOutput.success(data, "Internal news and external web retrieval completed");
    }

    private int upstreamLocalEvidenceCount(ToolInput input) {
        if (input == null || input.getContext() == null) return 0;
        Object raw = input.getContext().get("upstreamLocalEvidenceCount");
        if (raw instanceof Number number) return Math.max(0, number.intValue());
        try {
            return raw == null ? 0 : Math.max(0, Integer.parseInt(String.valueOf(raw)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String auditQuery(String query) {
        String normalized = query == null ? "" : query
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private String auditIdentifier(String value) {
        if (value == null || value.isBlank()) return "unavailable";
        String normalized = value.replace('\r', '_').replace('\n', '_').replace('\t', '_').trim();
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100) + "...";
    }

    private Map<String, Object> localItem(NewsDocument document) {
        Map<String, Object> item = NewsToolSupport.evidenceItem(document);
        item.put("resultType", "news");
        item.put("retrievalSource", "news_index");
        item.put("snippet", document.summary() == null || document.summary().isBlank()
            ? NewsToolSupport.abbreviate(document.content(), 500) : document.summary());
        return item;
    }

    private Map<String, Object> externalItem(TencentWebSearchClient.SearchPage page, String retrievalSource) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resultType", "web");
        item.put("documentKind", "external_web_page");
        item.put("retrievalSource", retrievalSource);
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
