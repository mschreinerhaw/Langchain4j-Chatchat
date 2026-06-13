package com.chatchat.mcpserver.web;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WebSearchExtractService {

    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[^\\s\\)\\]\\}>\"'，。；;,]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SITE_OPERATOR_PATTERN = Pattern.compile("(?i)(?:^|\\s)site\\s*:\\s*([^\\s]+)");

    private static final Set<String> PREFERRED_DOMAINS = Set.of(
        "docs.", "developer.", "learn.microsoft.com", "github.com", "wikipedia.org",
        "openai.com", "spring.io", "oracle.com", "ietf.org", "w3.org"
    );

    private final ToolRegistry toolRegistry;
    private final WebCrawlerService crawlerService;
    private final WebPageCacheService cacheService;
    private final WebCrawlerProperties crawlerProperties;
    private final InternetEvidenceService evidenceService;
    private final EvidenceReranker evidenceReranker;
    private final EvidenceContractService evidenceContractService;
    private final EvidenceObservabilityService evidenceObservabilityService;

    public WebSearchExtractService(ToolRegistry toolRegistry,
                                   WebCrawlerService crawlerService,
                                   WebPageCacheService cacheService,
                                   WebCrawlerProperties crawlerProperties,
                                   InternetEvidenceService evidenceService,
                                   EvidenceReranker evidenceReranker,
                                   EvidenceContractService evidenceContractService,
                                   EvidenceObservabilityService evidenceObservabilityService) {
        this.toolRegistry = toolRegistry;
        this.crawlerService = crawlerService;
        this.cacheService = cacheService;
        this.crawlerProperties = crawlerProperties;
        this.evidenceService = evidenceService;
        this.evidenceReranker = evidenceReranker;
        this.evidenceContractService = evidenceContractService;
        this.evidenceObservabilityService = evidenceObservabilityService;
    }

    /**
     * Searches and extracts web pages.
     *
     * @param query the query value
     * @param mode the mode value
     * @param topK the top k value
     * @return the operation result
     */
    public Map<String, Object> searchAndExtract(String query, String mode, int topK) {
        String normalizedQuery = normalizeQuery(query);
        String normalizedMode = normalizeMode(mode);
        int limit = Math.max(1, Math.min(20, topK <= 0 ? 5 : topK));
        String cacheIdentity = normalizedMode + "|" + limit + "|" + normalizedQuery;
        WebPageCacheService.CacheLookup cached = cacheService.get("search_extract", cacheIdentity);
        if (cached.hit()) {
            Map<String, Object> output = new LinkedHashMap<>(cached.data());
            output.put("cacheHit", true);
            output.put("cacheAgeSeconds", cached.ageSeconds());
            return output;
        }
        if ("cached".equals(normalizedMode)) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", normalizedQuery);
            output.put("mode", normalizedMode);
            output.put("cacheHit", false);
            output.put("results", List.of());
            output.put("pages", List.of());
            output.put("message", "No cached search_and_extract result found");
            return output;
        }

        QueryScope queryScope = analyzeQueryScope(normalizedQuery);
        List<String> rewrittenQueries = rewriteQueries(queryScope.searchQuery(), normalizedMode);
        Map<String, Object> searchOutput = runWebSearch(rewrittenQueries.get(0), searchResultLimit(normalizedMode, limit));
        List<Map<String, Object>> ranked = rank(extractSearchResults(searchOutput), limit, queryScope);
        List<Map<String, Object>> pages = extractPages(ranked, normalizedMode);
        List<String> referenceUrls = ranked.stream()
            .map(item -> stringValue(item.get("url")))
            .filter(url -> url != null && !url.isBlank())
            .distinct()
            .toList();
        List<String> chunks = pages.stream()
            .flatMap(page -> asStringList(page.get("chunks")).stream())
            .limit(Math.max(5, limit * 4L))
            .toList();
        InternetEvidenceService.EvidenceResult evidence = evidenceService.generateEvidence(
            normalizedQuery,
            ranked,
            pages,
            Math.max(10, limit * 4)
        );
        EvidenceReranker.RerankResult reranked = evidenceReranker.rerank(
            normalizedQuery,
            evidence.evidenceChunks(),
            Math.max(10, limit * 4)
        );
        EvidenceContractService.ContractResult contract = evidenceContractService.normalize(reranked.chunks());
        Map<String, Object> observability = evidenceObservabilityService.summarize(
            normalizedQuery,
            normalizedMode,
            ranked,
            pages,
            contract.chunks()
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", normalizedQuery);
        output.put("mode", normalizedMode);
        output.put("topK", limit);
        output.put("query_rewrites", rewrittenQueries);
        output.put("search_query", rewrittenQueries.get(0));
        output.put("target_site", queryScope.targetHost());
        output.put("cacheHit", false);
        output.put("reference_urls", referenceUrls);
        output.put("results", ranked);
        output.put("pages", pages);
        output.put("chunks", chunks);
        output.put("raw_evidence_chunks", evidence.evidenceChunks());
        output.put("reranked_evidence_chunks", reranked.chunks());
        output.put("evidence_chunks", contract.chunks());
        output.put("evidenceSnippets", contract.chunks());
        output.put("raw_citations", evidence.citations());
        output.put("citations", contract.citations());
        output.put("evidence_contract", contract.metadata());
        output.put("evidence_ranker", evidence.ranker());
        output.put("evidence_reranker", reranked.metadata());
        output.put("evidence_observability", observability);
        output.put("search", searchOutput);
        output.put("contentMode", pages.isEmpty() ? "search_snippets_only" : "search_and_crawl");
        cacheService.put("search_extract", cacheIdentity, output, crawlerProperties.getCacheTtlSeconds());
        return output;
    }

    private Map<String, Object> runWebSearch(String query, int limit) {
        if (!toolRegistry.hasTool("web_search")) {
            throw new IllegalStateException("web_search tool is not registered");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", query);
        parameters.put("num_results", limit);
        ToolOutput output = toolRegistry.executeEnhancedTool("web_search", ToolInput.builder()
            .rawInput(query)
            .parameters(parameters)
            .build());
        if (output == null || !output.isSuccess()) {
            String error = output == null ? "web_search returned no output" : output.getErrorMessage();
            throw new IllegalStateException(error == null || error.isBlank() ? "web_search failed" : error);
        }
        Object data = output.getData();
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return new LinkedHashMap<>(typed);
        }
        return Map.of("raw", data == null ? "" : data);
    }

    private List<Map<String, Object>> extractSearchResults(Map<String, Object> searchOutput) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        addMaps(candidates, searchOutput.get("results"));
        addMaps(candidates, searchOutput.get("items"));
        addMaps(candidates, searchOutput.get("organic_results"));
        Object referenceUrls = searchOutput.get("reference_urls");
        if (referenceUrls instanceof List<?> urls) {
            int rank = candidates.size() + 1;
            for (Object item : urls) {
                String url = stringValue(item);
                if (url == null || url.isBlank()) {
                    continue;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rank", rank++);
                result.put("url", url);
                result.put("title", url);
                result.put("snippet", "");
                candidates.add(result);
            }
        }

        Map<String, Map<String, Object>> byUrl = new LinkedHashMap<>();
        for (Map<String, Object> item : candidates) {
            String url = firstNonBlank(stringValue(item.get("url")), firstNonBlank(stringValue(item.get("link")), stringValue(item.get("href"))));
            if (url == null || url.isBlank() || byUrl.containsKey(url)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>(item);
            normalized.put("url", url);
            normalized.put("rank", numberValue(item.get("rank"), byUrl.size() + 1));
            byUrl.put(url, normalized);
        }
        return new ArrayList<>(byUrl.values());
    }

    private List<Map<String, Object>> rank(List<Map<String, Object>> results, int limit, QueryScope queryScope) {
        List<Map<String, Object>> scoped = applyTargetScope(results, queryScope);
        return scoped.stream()
            .peek(item -> item.put("score", score(item)))
            .sorted(Comparator.comparingDouble((Map<String, Object> item) -> numberValue(item.get("score"), 0).doubleValue()).reversed())
            .limit(limit)
            .toList();
    }

    private List<Map<String, Object>> applyTargetScope(List<Map<String, Object>> results, QueryScope queryScope) {
        if (queryScope.targetHost() == null || queryScope.targetHost().isBlank()) {
            return results;
        }
        List<Map<String, Object>> scoped = results.stream()
            .filter(item -> sameSearchDomain(stringValue(item.get("url")), queryScope.targetHost()))
            .toList();
        return scoped.isEmpty() ? results : scoped;
    }

    private double score(Map<String, Object> item) {
        int rank = numberValue(item.get("rank"), 100).intValue();
        String host = host(stringValue(item.get("url")));
        double score = 1.0d / Math.max(1, rank);
        if (host != null && PREFERRED_DOMAINS.stream().anyMatch(host::contains)) {
            score += 0.2d;
        }
        String snippet = stringValue(item.get("snippet"));
        if (snippet != null && snippet.length() > 80) {
            score += 0.05d;
        }
        return Math.round(score * 10000.0d) / 10000.0d;
    }

    private List<Map<String, Object>> extractPages(List<Map<String, Object>> ranked, String mode) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        int crawlLimit = switch (mode) {
            case "deep" -> ranked.size();
            case "fast" -> Math.min(1, ranked.size());
            default -> 0;
        };
        List<Map<String, Object>> pages = new ArrayList<>();
        for (Map<String, Object> item : ranked.subList(0, crawlLimit)) {
            String url = stringValue(item.get("url"));
            try {
                Map<String, Object> page = crawlerService.crawl(url, false, crawlerProperties.getTimeoutMs());
                page.remove("html");
                pages.add(page);
                item.put("pageFetched", true);
                item.put("contentLength", page.get("contentLength"));
            } catch (Exception ex) {
                item.put("pageFetched", false);
                item.put("pageFetchError", ex.getMessage());
            }
        }
        return pages;
    }

    private List<String> rewriteQueries(String query, String mode) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(query);
        if ("deep".equals(mode)) {
            queries.add(query + " official documentation");
            queries.add(query + " latest reference");
        }
        return new ArrayList<>(queries);
    }

    private int searchResultLimit(String mode, int topK) {
        return "deep".equals(mode) ? Math.min(20, Math.max(topK, topK * 2)) : topK;
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        return query.replaceAll("\\s+", " ").trim();
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "fast";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fast", "deep", "cached" -> normalized;
            default -> "fast";
        };
    }

    private QueryScope analyzeQueryScope(String query) {
        String targetUrl = firstUrl(query);
        String targetHost = targetUrl == null ? null : normalizedSearchHost(host(targetUrl));
        Matcher siteMatcher = SITE_OPERATOR_PATTERN.matcher(query);
        if ((targetHost == null || targetHost.isBlank()) && siteMatcher.find()) {
            targetHost = normalizedSearchHost(siteMatcher.group(1));
        }
        if (targetHost == null || targetHost.isBlank() || containsSiteOperator(query)) {
            return new QueryScope(query, targetHost);
        }
        String keyword = cleanupSiteKeyword(query);
        String searchQuery = keyword.isBlank() ? "site:" + targetHost : "site:" + targetHost + " " + keyword;
        return new QueryScope(searchQuery, targetHost);
    }

    private String cleanupSiteKeyword(String query) {
        String value = query == null ? "" : query.trim();
        value = HTTP_URL_PATTERN.matcher(value).replaceAll(" ");
        value = SITE_OPERATOR_PATTERN.matcher(value).replaceAll(" ");
        for (String phrase : List.of(
            "\u4ece\u8fd9\u4e2a\u7f51\u7ad9\u627e",
            "\u4ece\u8fd9\u4e2a\u7f51\u7ad9\u641c\u7d22",
            "\u8fd9\u4e2a\u7f51\u7ad9",
            "\u7f51\u7ad9",
            "\u641c\u7d22",
            "\u67e5\u627e",
            "\u67e5\u8be2",
            "\u4ece"
        )) {
            value = value.replace(phrase, " ");
        }
        return value.replaceAll("(?i)\\b(from|within|inside|website|site|search|find|lookup|look up|on|in)\\b", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String firstUrl(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Matcher matcher = HTTP_URL_PATTERN.matcher(query);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean containsSiteOperator(String query) {
        return query != null && SITE_OPERATOR_PATTERN.matcher(query).find();
    }

    private boolean sameSearchDomain(String url, String targetHost) {
        String host = normalizedSearchHost(host(url));
        String target = normalizedSearchHost(targetHost);
        return host != null && target != null && (host.equals(target) || host.endsWith("." + target));
    }

    private String normalizedSearchHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("www.")) {
            value = value.substring(4);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value.isBlank() ? null : value;
    }

    private record QueryScope(String searchQuery, String targetHost) {
    }

    private void addMaps(List<Map<String, Object>> values, Object source) {
        if (!(source instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                values.add(new LinkedHashMap<>(typed));
            }
        }
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(this::stringValue)
            .filter(text -> text != null && !text.isBlank())
            .toList();
    }

    private Number numberValue(Object value, Number fallback) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
