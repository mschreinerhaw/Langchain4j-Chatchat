package com.chatchat.runtime.news.tool;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.chatchat.runtime.news.search.DisabledWebSearchCache;
import com.chatchat.runtime.news.search.NewsRelevanceRanker;
import com.chatchat.runtime.news.search.NewsLocalQueryPlanner;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Public bridge: local news first, internal external_web_search only when evidence is insufficient. */
@Component
public class WebSearchToolExecutor implements NewsToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(WebSearchToolExecutor.class);
    private final NewsDocumentStore store;
    private final NewsRuntimeProperties properties;
    private final ExternalWebSearchToolExecutor externalWebSearch;

    @Autowired
    public WebSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties,
                                 ExternalWebSearchToolExecutor externalWebSearch) {
        this.store = store;
        this.properties = properties;
        this.externalWebSearch = externalWebSearch;
    }

    public WebSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties,
                                 TencentWebSearchClient externalSearch, WebSearchCache cache) {
        this(store, properties, new ExternalWebSearchToolExecutor(properties, externalSearch, cache));
    }

    public WebSearchToolExecutor(NewsDocumentStore store, NewsRuntimeProperties properties,
                                 TencentWebSearchClient externalSearch) {
        this(store, properties, externalSearch, new DisabledWebSearchCache());
    }

    @Override
    public ToolOutput execute(ToolInput input) {
        CancellationSupport.throwIfCancelled(NewsToolNames.WEB_SEARCH);
        String query = input.getParameterAsString("query", "").trim();
        if (query.isBlank()) return ToolOutput.failure("query parameter is required");
        boolean localEnabled = properties.getOpenSearch().isEnabled();
        boolean externalAvailable = externalWebSearch.available();
        if (!localEnabled && !externalAvailable) return NewsToolSupport.unavailable(NewsToolNames.WEB_SEARCH);

        int size = NewsToolSupport.boundedInt(input.getParameterAsNumber("num_results"), 10, 1, 50);
        List<Map<String, Object>> local = new ArrayList<>();
        int localCandidateCount = 0;
        List<Map<String, Object>> external = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean localSucceeded = false;
        NewsLocalQueryPlanner.QueryPlan localQueryPlan = NewsLocalQueryPlanner.plan(
            query, firstPresent(input, "queryTerms", "query_terms", "keywords", "searchTerms"),
            properties.getWebSearch().getMaximumLocalQueryTerms());
        if (localEnabled) {
            Map<String, NewsDocument> mergedCandidates = new LinkedHashMap<>();
            Map<String, NewsDocument> qualifiedCandidates = new LinkedHashMap<>();
            Map<String, NewsRelevanceRanker.RankedNews> perQueryRankings = new LinkedHashMap<>();
            Map<String, Set<String>> matchedQueries = new LinkedHashMap<>();
            for (String localQuery : localQueryPlan.queries()) {
                CancellationSupport.throwIfCancelled(NewsToolNames.WEB_SEARCH);
                try {
                    List<NewsDocument> recalled = store.search(
                        new NewsSearchQuery(localQuery, List.of(), null, null, List.of(), size));
                    localSucceeded = true;
                    for (NewsDocument document : recalled == null ? List.<NewsDocument>of() : recalled) {
                        String key = localDocumentKey(document);
                        mergedCandidates.putIfAbsent(key, document);
                    }
                    for (NewsRelevanceRanker.RankedNews ranked : NewsRelevanceRanker.rank(
                        localQuery, recalled, size)) {
                        String key = localDocumentKey(ranked.document());
                        qualifiedCandidates.putIfAbsent(key, ranked.document());
                        perQueryRankings.putIfAbsent(key, ranked);
                        matchedQueries.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(localQuery);
                    }
                } catch (Exception ex) {
                    CancellationSupport.rethrowIfCancelled(ex, "web_search local retrieval");
                    warnings.add("news_index[" + auditQuery(localQuery) + "]: " + safe(ex));
                }
            }
            localCandidateCount = mergedCandidates.size();
            List<NewsRelevanceRanker.RankedNews> aggregated = new ArrayList<>(
                NewsRelevanceRanker.rank(query, List.copyOf(qualifiedCandidates.values()), size));
            Set<String> aggregatedKeys = new LinkedHashSet<>();
            aggregated.forEach(ranked -> aggregatedKeys.add(localDocumentKey(ranked.document())));
            for (Map.Entry<String, NewsRelevanceRanker.RankedNews> entry : perQueryRankings.entrySet()) {
                if (aggregated.size() >= size) break;
                if (aggregatedKeys.add(entry.getKey())) aggregated.add(entry.getValue());
            }
            aggregated.forEach(ranked -> local.add(localItem(
                ranked, matchedQueries.getOrDefault(localDocumentKey(ranked.document()), Set.of()))));
        }

        CancellationSupport.throwIfCancelled(NewsToolNames.WEB_SEARCH);
        boolean forceExternal = properties.getWebSearch().getCache().isForceExternal();
        int requiredLocalResults = Math.min(size,
            Math.max(1, properties.getWebSearch().getMinimumLocalResults()));
        int upstreamLocalEvidenceCount = upstreamLocalEvidenceCount(input);
        int qualifiedLocalNewsCount = local.size();
        int totalLocalEvidenceCount = qualifiedLocalNewsCount + upstreamLocalEvidenceCount;
        boolean localSufficient = (localSucceeded || upstreamLocalEvidenceCount > 0)
            && totalLocalEvidenceCount >= requiredLocalResults;
        boolean externalSupplementRequired = forceExternal || !localSufficient;
        log.info("webSearchBridgeRoute query=\"{}\" requested={} localCandidateCount={} qualifiedLocalNewsCount={} "
                + "upstreamLocalEvidenceCount={} requiredLocalCount={} externalTool={} externalRequired={}",
            auditQuery(query), size, localCandidateCount, qualifiedLocalNewsCount,
            upstreamLocalEvidenceCount, requiredLocalResults,
            ExternalWebSearchToolExecutor.INTERNAL_TOOL_NAME, externalSupplementRequired);

        boolean externalSucceeded = false;
        Map<String, Object> externalData = Map.of();
        if (externalSupplementRequired && externalAvailable) {
            ToolOutput delegated = externalWebSearch.execute(input);
            if (delegated != null && delegated.isSuccess()) {
                externalData = map(delegated.getData());
                external.addAll(resultMaps(externalData.get("results")));
                warnings.addAll(stringList(externalData.get("warnings")));
                externalSucceeded = true;
            } else {
                warnings.add("tencent_wsa via " + ExternalWebSearchToolExecutor.INTERNAL_TOOL_NAME + ": "
                    + (delegated == null ? "no response" : String.valueOf(delegated.getErrorMessage())));
            }
        } else if (!externalSupplementRequired) {
            log.info("internalExternalWebSearchSkipped query=\"{}\" reason=local_evidence_sufficient", auditQuery(query));
        } else {
            log.info("internalExternalWebSearchSkipped query=\"{}\" reason=provider_unavailable", auditQuery(query));
        }

        CancellationSupport.throwIfCancelled(NewsToolNames.WEB_SEARCH);
        if (!localSucceeded && !externalSucceeded) {
            return ToolOutput.failure("Web retrieval unavailable: " + String.join("; ", warnings));
        }
        List<Map<String, Object>> results = fuse(local, external, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("provider", "chatchat-runtime-news");
        data.put("mode", localSucceeded && externalSucceeded ? "hybrid_news_and_web"
            : externalSucceeded ? String.valueOf(externalData.getOrDefault("mode", "external_web_search"))
            : "news_index");
        data.put("count", results.size());
        data.put("newsIndexCount", qualifiedLocalNewsCount);
        data.put("newsIndexCandidateCount", localCandidateCount);
        data.put("qualifiedLocalNewsCount", qualifiedLocalNewsCount);
        data.put("localEvidenceQualityStrategy", NewsRelevanceRanker.STRATEGY);
        data.put("localSearchStrategy", NewsLocalQueryPlanner.STRATEGY);
        data.put("localSearchTerms", localQueryPlan.keywords());
        data.put("localSearchQueryCount", localEnabled ? localQueryPlan.queries().size() : 0);
        data.put("externalWebCount", external.size());
        data.put("webSearchCacheEnabled", externalData.getOrDefault("webSearchCacheEnabled", false));
        data.put("webSearchCacheHit", externalData.getOrDefault("webSearchCacheHit", false));
        data.put("forcedExternalSearch", forceExternal);
        data.put("localEvidenceSufficient", localSufficient);
        data.put("upstreamLocalEvidenceCount", upstreamLocalEvidenceCount);
        data.put("totalLocalEvidenceCount", totalLocalEvidenceCount);
        data.put("minimumLocalResults", requiredLocalResults);
        data.put("externalSearchRole", "supplementary_fallback");
        data.put("externalSearchRequired", externalSupplementRequired);
        data.put("externalSearchTool", ExternalWebSearchToolExecutor.INTERNAL_TOOL_NAME);
        data.put("externalSearchToolVisibility", "internal_bridge_only");
        copyIfPresent(externalData, data, "cachedQuery", "cacheSimilarity", "externalProvider",
            "externalRequestId", "externalVersion");
        data.put("results", results);
        data.put("reference_urls", NewsToolSupport.evidenceUrls(results));
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        return ToolOutput.success(data, "Internal news and external web retrieval completed");
    }

    private int upstreamLocalEvidenceCount(ToolInput input) {
        if (input == null || input.getContext() == null) return 0;
        Object raw = input.getContext().get("upstreamLocalEvidenceCount");
        if (raw instanceof Number number) return Math.max(0, number.intValue());
        try { return raw == null ? 0 : Math.max(0, Integer.parseInt(String.valueOf(raw))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private Map<String, Object> localItem(NewsRelevanceRanker.RankedNews ranked,
                                          Set<String> matchedQueries) {
        NewsDocument document = ranked.document();
        Map<String, Object> item = NewsToolSupport.evidenceItem(document);
        item.put("resultType", "news");
        item.put("retrievalSource", "news_index");
        item.put("relevanceScore", ranked.score());
        item.put("relevanceCoverage", ranked.coverage());
        item.put("matchedTermCount", ranked.matchedTerms());
        item.put("relevanceStrategy", NewsRelevanceRanker.STRATEGY);
        item.put("matchedLocalQueries", matchedQueries == null ? List.of() : List.copyOf(matchedQueries));
        item.put("snippet", document.summary() == null || document.summary().isBlank()
            ? NewsToolSupport.abbreviate(document.content(), 500) : document.summary());
        return item;
    }

    private String localDocumentKey(NewsDocument document) {
        if (document == null) return "null";
        if (document.documentId() != null && !document.documentId().isBlank()) {
            return "id:" + document.documentId();
        }
        if (document.sourceUrl() != null && !document.sourceUrl().isBlank()) {
            return "url:" + document.sourceUrl().trim().toLowerCase(Locale.ROOT).replaceFirst("[/#]+$", "");
        }
        return "title:" + String.valueOf(document.title()).trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "");
    }

    private Object firstPresent(ToolInput input, String... keys) {
        if (input == null || input.getParameters() == null) return null;
        for (String key : keys) {
            if (input.getParameters().containsKey(key) && input.getParameters().get(key) != null) {
                return input.getParameters().get(key);
            }
        }
        return null;
    }

    private List<Map<String, Object>> fuse(List<Map<String, Object>> local,
                                           List<Map<String, Object>> external, int limit) {
        Map<String, RankedItem> fused = new LinkedHashMap<>();
        addRanked(fused, local);
        addRanked(fused, external);
        return fused.values().stream().sorted(Comparator.comparingDouble(RankedItem::score).reversed())
            .limit(limit).map(RankedItem::item).toList();
    }

    private void addRanked(Map<String, RankedItem> fused, List<Map<String, Object>> items) {
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            String key = dedupeKey(item);
            double contribution = 1D / (61D + index);
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
        return "title:" + String.valueOf(item.getOrDefault("title", "")).trim()
            .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultMaps(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) if (item != null) result.add(String.valueOf(item));
        return result;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private String auditQuery(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private String safe(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private record RankedItem(Map<String, Object> item, double score) { }
}
