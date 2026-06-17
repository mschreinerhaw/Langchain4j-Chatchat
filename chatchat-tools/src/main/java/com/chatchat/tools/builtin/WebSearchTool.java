package com.chatchat.tools.builtin;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.tools.playwright.PlaywrightBrowserSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

/**
 * Web Search Tool implementation
 */
@Slf4j
class WebSearchTool implements ToolRegistry.EnhancedTool {

    private static final String CONTRACT_VERSION = "web_evidence_v1";
    private static final String SEARCH_STAGE_FULL = "full";
    private static final String SEARCH_STAGE_PRIMARY = "primary";
    private static final String SEARCH_STAGE_SITE_SEARCH = "site_search";
    private static final String SITE_SEARCH_MODE_JAVA = "java";
    private static final String SITE_SEARCH_MODE_BROWSER = "browser";
    private static final String SITE_SEARCH_MODE_AUTO = "auto";

    private final WebSearchToolProperties properties;
    private final ObjectMapper objectMapper;
    private final Semaphore rateSemaphore;
    private final Object rateLock = new Object();
    private final Object cookieLock = new Object();
    private final Map<String, Map<String, String>> cookieJar = new HashMap<>();
    private final Map<String, ProxyRuntimeState> proxyStates = new HashMap<>();
    private final AtomicInteger proxyCursor = new AtomicInteger();
    private final AtomicInteger dailyCalls = new AtomicInteger();
    private final PlaywrightBrowserSupport playwrightSupport = new PlaywrightBrowserSupport("web_search");
    private final PersistentBingBrowserSession persistentBingBrowserSession = new PersistentBingBrowserSession();
    private volatile LocalDate dailyWindow = LocalDate.now();
    private volatile long lastRequestAtMs;
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[^\\s\\)\\]\\}>\"'\\uFF0C\\u3002\\uFF1B;,]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SITE_OPERATOR_PATTERN = Pattern.compile("(?i)(?:^|\\s)site\\s*:\\s*([^\\s]+)");

    /**
     * Creates a new WebSearchTool instance.
     *
     * @param properties the properties value
     * @param objectMapper the object mapper value
     */
    WebSearchTool(WebSearchToolProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.rateSemaphore = new Semaphore(Math.max(1, properties.getRateLimit().getMaxConcurrency()), true);
        loadCookies();
    }

    /**
     * Returns the metadata.
     *
     * @return the metadata
     */
    @Override
    public ToolMetadata getMetadata() {
        return null;
    }

    /**
     * Executes the execute.
     *
     * @param input the input value
     * @return the operation result
     */
    @Override
    public ToolOutput execute(ToolInput input) {
        try {
            if (!properties.isEnabled()) {
                return ToolOutput.failure("web_search tool is disabled");
            }
            String query = input.getParameterAsString("query", "");
            Number requestedResults = input.getParameterAsNumber("num_results");
            int numResults = requestedResults == null
                ? properties.getMaxResults()
                : requestedResults.intValue();
            numResults = Math.max(1, Math.min(properties.getMaxResults(), numResults));

            if (query.isEmpty()) {
                return ToolOutput.failure("Search query is required");
            }

            String searchStage = normalizeSearchStage(input.getParameterAsString("search_stage", SEARCH_STAGE_FULL));
            Map<String, Object> result = SEARCH_STAGE_SITE_SEARCH.equals(searchStage)
                ? performSiteSearch(input, query, numResults)
                : performWebSearch(
                    input,
                    query,
                    numResults,
                    includeSiteSearch(input, searchStage),
                    includePageFetch(input, searchStage)
                );
            return ToolOutput.success(result, buildSearchMessage(result));

        } catch (Exception e) {
            return ToolOutput.failure(e);
        }
    }

    /**
     * Performs the perform web search operation.
     *
     * @param query the query value
     * @param numResults the num results value
     * @return the operation result
     * @throws Exception if the operation fails
     */
    private Map<String, Object> performWebSearch(ToolInput input,
                                                 String query,
                                                 int numResults,
                                                 boolean includeSiteSearch,
                                                 boolean includePageFetch) throws Exception {
        String provider = properties.getProvider() == null
            ? "duckduckgo_html"
            : properties.getProvider().trim().toLowerCase(Locale.ROOT);

        WebSearchQueryIntent queryIntent = analyzeSearchQuery(query);
        WebSearchRequestContext context = requestContext(input, query);
        String webSearchMode = webSearchMode(input);
        String siteSearchMode = siteSearchMode(input);
        List<SearchAttempt> attempts = buildSearchAttempts(provider, properties.getEndpoint());
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> networkAudit = new ArrayList<>();
        for (SearchAttempt attempt : attempts) {
            try {
                long startedAt = System.currentTimeMillis();
                log.info("Web search provider attempt started provider={} endpoint={} query={} numResults={} fetchPages={} maxPagesToFetch={}",
                    attempt.provider(),
                    attempt.endpoint(),
                    query,
                    numResults,
                    includePageFetch,
                    properties.getMaxPagesToFetch());
                Map<String, Object> result = performWebSearchAttempt(
                    attempt.provider(),
                    attempt.endpoint(),
                    queryIntent,
                    numResults,
                    errors,
                    context,
                    networkAudit,
                    includeSiteSearch,
                    includePageFetch,
                    webSearchMode,
                    siteSearchMode
                );
                log.info("Web search provider attempt succeeded provider={} durationMs={} resultCount={} pageExcerptCount={}",
                    attempt.provider(),
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    result.get("count"),
                    result.get("page_excerpt_count"));
                return result;
            } catch (Exception ex) {
                errors.add(attempt.provider() + ": " + ex.getMessage());
                log.warn("Web search provider attempt failed provider={} error={}", attempt.provider(), ex.getMessage());
            }
        }
        throw new IllegalStateException("All web search providers failed: " + String.join("; ", errors));
    }

    /**
     * Performs the perform web search attempt operation.
     *
     * @param provider the provider value
     * @param endpoint the endpoint value
     * @param query the query value
     * @param numResults the num results value
     * @param previousErrors the previous errors value
     * @return the operation result
     * @throws Exception if the operation fails
     */
    private Map<String, Object> performWebSearchAttempt(String provider,
                                                        String endpoint,
                                                        WebSearchQueryIntent queryIntent,
                                                        int numResults,
                                                        List<String> previousErrors,
                                                        WebSearchRequestContext context,
                                                        List<Map<String, Object>> networkAudit,
                                                        boolean includeSiteSearch,
                                                        boolean includePageFetch,
                                                        String webSearchMode,
                                                        String siteSearchMode) throws Exception {
        String query = queryIntent.originalQuery();
        String searchQuery = queryIntent.searchQuery();
        String siteQuery = queryIntent.siteSearchQuery();
        SearchStrategy searchStrategy = searchStrategy(queryIntent);
        HtmlResponse searchResponse = sendSearchEngineRequest(
            provider,
            endpoint,
            searchQuery,
            context,
            networkAudit,
            webSearchMode
        );
        Document document = searchResponse.document();

        int referenceLimit = Math.max(0, Math.min(10, properties.getMaxResults()));
        int fetchLimit = Math.max(numResults, referenceLimit);

        List<Map<String, Object>> fetchedResults = switch (provider) {
            case "duckduckgo_html" -> parseDuckDuckGoResults(document, fetchLimit);
            case "bing_html" -> parseBingResults(document, fetchLimit);
            default -> throw new IllegalArgumentException("Unsupported web search provider: " + properties.getProvider());
        };
        if (fetchedResults.isEmpty()) {
            fetchedResults = fallbackExtractAllLinks(document, searchQuery, fetchLimit);
        }
        List<Map<String, Object>> primaryResults = filterAllowedResults(
            primaryResults(fetchedResults, queryIntent),
            context,
            networkAudit,
            searchQuery,
            "primary_result_filter"
        );
        SearchResultRelevance searchResultRelevance = assessSearchResultRelevance(primaryResults, searchQuery);
        List<Map<String, Object>> siteSearchSeeds = siteSearchSeeds(primaryResults, queryIntent);
        List<Map<String, Object>> siteSearchResults = includeSiteSearch
            ? runTargetedKnownSiteSearch(
                queryIntent,
                siteQuery,
                siteSearchSeeds,
                fetchLimit,
                context,
                networkAudit,
                siteSearchMode
            )
            : List.of();
        List<Map<String, Object>> discoveredSiteSearchResults = includeSiteSearch
            ? discoverSiteSearchResults(
                siteSearchSeeds,
                siteQuery,
                fetchLimit,
                context,
                networkAudit,
                true,
                siteSearchMode
            )
            : List.of();
        siteSearchResults = mergeSearchResults(siteSearchResults, discoveredSiteSearchResults, fetchLimit);
        List<Map<String, Object>> mergedCandidates = filterAllowedResults(
            mergeSearchResults(primaryResults, siteSearchResults, fetchLimit),
            context,
            networkAudit,
            searchQuery,
            "merged_result_filter"
        );
        List<Map<String, Object>> pageExcerpts = includePageFetch
            ? fetchPageExcerpts(mergedCandidates, query, context, networkAudit)
            : List.of();
        List<Map<String, Object>> rankedResults = filterAllowedResults(
            rankSearchResults(mergedCandidates, pageExcerpts, searchQuery, fetchLimit, searchStrategy),
            context,
            networkAudit,
            searchQuery,
            "final_result_filter"
        );
        attachUrlEvidence(rankedResults, pageExcerpts, searchQuery, searchStrategy);
        List<Map<String, Object>> results = rankedResults.size() <= numResults
            ? rankedResults
            : new ArrayList<>(rankedResults.subList(0, numResults));
        List<String> referenceUrls = rankedResults.stream()
            .map(item -> item.get("url"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(url -> !url.isBlank())
            .distinct()
            .limit(referenceLimit)
            .toList();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", CONTRACT_VERSION);
        output.put("requestContext", requestContextMap(context));
        output.put("governance", governanceMap(context));
        output.put("query", query);
        output.put("search_query", searchQuery);
        output.put("site_search_query", siteQuery);
        output.put("query_strategy", searchStrategy.toMap());
        output.put("query_plan", queryPlan(queryIntent, searchStrategy));
        output.put("target_site", queryIntent.targetHost());
        output.put("provider", provider);
        output.put("configuredProvider", properties.getProvider());
        output.put("fallbackUsed", previousErrors != null && !previousErrors.isEmpty());
        output.put("providerErrors", previousErrors == null ? List.of() : previousErrors);
        output.put("count", results.size());
        output.put("reference_url_count", referenceUrls.size());
        output.put("reference_urls", referenceUrls);
        output.put("search_result_useful", searchResultRelevance.useful());
        output.put("search_result_relevance", searchResultRelevance.toMap());
        output.put("search_stage", includeSiteSearch || includePageFetch ? SEARCH_STAGE_FULL : SEARCH_STAGE_PRIMARY);
        output.put("web_search_mode", webSearchMode);
        output.put("site_search_mode", includeSiteSearch ? siteSearchMode : SITE_SEARCH_MODE_JAVA);
        output.put("site_search_enabled", includeSiteSearch && properties.getSiteSearch().isEnabled());
        output.put("primary_result_count", primaryResults.size());
        output.put("site_search_result_count", siteSearchResults.size());
        output.put("page_fetch_enabled", includePageFetch && properties.isFetchPages());
        output.put("page_excerpt_count", pageExcerpts.size());
        output.put("final_result_count", rankedResults.size());
        output.put("contentMode", contentMode(pageExcerpts, siteSearchResults));
        output.put("rerank_engine", "heuristic_evidence_v2");
        output.put("structured_text", structuredSearchText(results, pageExcerpts, searchResultRelevance));
        output.put("web_search_audit", properties.getAudit().isIncludeInResult() ? auditForResult(networkAudit, context) : List.of());
        output.put("primaryResults", primaryResults);
        output.put("siteSearchResults", siteSearchResults);
        output.put("pageEvidence", pageExcerpts);
        output.put("finalResults", rankedResults);
        output.put("pageExcerpts", pageExcerpts);
        output.put("evidenceSnippets", pageExcerpts);
        output.put("results", results);
        return output;
    }

    private Map<String, Object> performSiteSearch(ToolInput input, String query, int numResults) {
        WebSearchQueryIntent queryIntent = analyzeSearchQuery(query);
        SearchStrategy searchStrategy = searchStrategy(queryIntent);
        WebSearchRequestContext context = requestContext(input, query);
        String siteSearchMode = siteSearchMode(input);
        List<Map<String, Object>> networkAudit = new ArrayList<>();
        int resultLimit = Math.max(1, Math.min(properties.getMaxResults(), numResults));
        String siteQuery = firstNonBlank(
            input.getParameterAsString("site_search_query", ""),
            queryIntent.siteSearchQuery()
        );
        List<Map<String, Object>> seedResults = filterAllowedResults(
            seedResults(input, queryIntent),
            context,
            networkAudit,
            siteQuery,
            "seed_result_filter"
        );
        if (seedResults.isEmpty()) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("contractVersion", CONTRACT_VERSION);
            output.put("requestContext", requestContextMap(context));
            output.put("governance", governanceMap(context));
            output.put("query", query);
            output.put("site_search_query", siteQuery);
            output.put("target_site", queryIntent.targetHost());
            output.put("search_stage", SEARCH_STAGE_SITE_SEARCH);
            output.put("site_search_mode", siteSearchMode);
            output.put("count", 0);
            output.put("reference_url_count", 0);
            output.put("reference_urls", List.of());
            output.put("candidate_url_count", 0);
            output.put("crawl_candidates", List.of());
            output.put("results", List.of());
            output.put("structured_text", "site_search_result_count: 0\nmessage: no seed urls selected");
            output.put("model_selection_text", "No site-search candidate URLs were found. Do not call web_crawler unless another reliable URL is available.");
            output.put("web_search_audit", properties.getAudit().isIncludeInResult() ? auditForResult(networkAudit, context) : List.of());
            output.put("message", "No seed URLs were provided for site search");
            return output;
        }

        List<Map<String, Object>> siteSearchResults = new ArrayList<>();
        siteSearchResults.addAll(runTargetedKnownSiteSearch(
            queryIntent,
            siteQuery,
            seedResults,
            resultLimit,
            context,
            networkAudit,
            siteSearchMode
        ));
        siteSearchResults = filterAllowedResults(mergeSearchResults(siteSearchResults, discoverSiteSearchResults(
            seedResults,
            siteQuery,
            resultLimit,
            context,
            networkAudit,
            true,
            siteSearchMode
        ), resultLimit), context, networkAudit, siteQuery, "site_result_filter");

        List<Map<String, Object>> pageExcerpts = properties.getUrlEvidence().isFetchForSiteSearch()
            ? fetchPageExcerpts(siteSearchResults, siteQuery, context, networkAudit, resultLimit)
            : List.of();
        siteSearchResults = filterAllowedResults(
            rankSearchResults(siteSearchResults, pageExcerpts, siteQuery, resultLimit, searchStrategy),
            context,
            networkAudit,
            siteQuery,
            "site_final_result_filter"
        );
        attachUrlEvidence(siteSearchResults, pageExcerpts, siteQuery, searchStrategy);

        List<String> referenceUrls = siteSearchResults.stream()
            .map(item -> item.get("url"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(url -> !url.isBlank())
            .distinct()
            .limit(Math.max(0, Math.min(10, properties.getMaxResults())))
            .toList();
        SearchResultRelevance relevance = assessSearchResultRelevance(siteSearchResults, siteQuery);
        List<Map<String, Object>> crawlCandidates = crawlCandidates(siteSearchResults, resultLimit);
        String modelSelectionText = siteSearchModelSelectionText(siteQuery, crawlCandidates);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractVersion", CONTRACT_VERSION);
        output.put("requestContext", requestContextMap(context));
        output.put("governance", governanceMap(context));
        output.put("query", query);
        output.put("site_search_query", siteQuery);
        output.put("query_strategy", searchStrategy.toMap());
        output.put("query_plan", queryPlan(queryIntent, searchStrategy));
        output.put("target_site", queryIntent.targetHost());
        output.put("search_stage", SEARCH_STAGE_SITE_SEARCH);
        output.put("site_search_mode", siteSearchMode);
        output.put("count", siteSearchResults.size());
        output.put("reference_url_count", referenceUrls.size());
        output.put("reference_urls", referenceUrls);
        output.put("candidate_url_count", crawlCandidates.size());
        output.put("search_result_useful", relevance.useful());
        output.put("search_result_relevance", relevance.toMap());
        output.put("site_search_enabled", properties.getSiteSearch().isEnabled());
        output.put("site_search_result_count", siteSearchResults.size());
        output.put("page_fetch_enabled", properties.isFetchPages() && properties.getUrlEvidence().isFetchForSiteSearch());
        output.put("page_excerpt_count", pageExcerpts.size());
        output.put("contentMode", siteSearchResults.isEmpty() ? "site_search_empty" : "site_search_enriched");
        output.put("rerank_engine", "heuristic_evidence_v2");
        output.put("structured_text", structuredSearchText(siteSearchResults, pageExcerpts, relevance));
        output.put("model_selection_text", modelSelectionText);
        output.put("web_search_audit", properties.getAudit().isIncludeInResult() ? auditForResult(networkAudit, context) : List.of());
        output.put("pageEvidence", pageExcerpts);
        output.put("pageExcerpts", pageExcerpts);
        output.put("evidenceSnippets", pageExcerpts);
        output.put("seed_results", seedResults);
        output.put("crawl_candidates", crawlCandidates);
        output.put("results", siteSearchResults);
        return output;
    }

    private WebSearchQueryIntent analyzeSearchQuery(String query) {
        String original = query == null ? "" : query.trim();
        String targetUrl = firstUrl(original);
        String submittedSearchQuery = submittedSearchEngineQuery(original, targetUrl);
        if (submittedSearchQuery != null && !submittedSearchQuery.isBlank()) {
            return new WebSearchQueryIntent(original, submittedSearchQuery, submittedSearchQuery, null, null);
        }
        if (isKnownSearchEngineUrl(targetUrl)) {
            targetUrl = null;
        }
        String targetHost = targetUrl == null ? null : normalizedSearchHost(hostOf(targetUrl));
        Matcher siteMatcher = SITE_OPERATOR_PATTERN.matcher(original);
        if ((targetHost == null || targetHost.isBlank()) && siteMatcher.find()) {
            targetHost = normalizedSearchHost(siteMatcher.group(1));
            targetUrl = "https://" + targetHost + "/";
        }
        NaturalTargetSite naturalTargetSite = null;
        if (targetHost == null || targetHost.isBlank()) {
            naturalTargetSite = naturalTargetSite(original);
            if (naturalTargetSite != null) {
                targetHost = naturalTargetSite.host();
                targetUrl = naturalTargetSite.url();
            }
        }

        String keyword = cleanupNaturalTargetSitePhrases(cleanupSiteKeyword(original), targetHost);
        String searchQuery = original;
        if (targetHost != null && !targetHost.isBlank() && !containsSiteOperator(original)) {
            searchQuery = keyword.isBlank()
                ? "site:" + targetHost
                : "site:" + targetHost + " " + keyword;
        }
        String siteSearchQuery = keyword;
        return new WebSearchQueryIntent(original, searchQuery, siteSearchQuery, targetUrl, targetHost);
    }

    private String submittedSearchEngineQuery(String original, String targetUrl) {
        if (!isKnownSearchEngineUrl(targetUrl)) {
            return null;
        }
        String fromUrl = searchQueryFromUrl(targetUrl);
        if (fromUrl != null && !fromUrl.isBlank()) {
            return fromUrl.trim();
        }
        String remainder = HTTP_URL_PATTERN.matcher(firstNonBlank(original, "")).replaceFirst(" ").trim();
        if (remainder.isBlank()) {
            return null;
        }
        remainder = remainder.replaceFirst(
            "(?i)^\\s*(?:query|q|keyword|keywords|wd|word|p|search|搜索|查询)\\s*[:=：]\\s*",
            ""
        ).trim();
        return remainder.isBlank() ? null : remainder;
    }

    private String searchQueryFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int question = url.indexOf('?');
        if (question < 0 || question == url.length() - 1) {
            return null;
        }
        int fragment = url.indexOf('#', question + 1);
        String rawQuery = fragment < 0 ? url.substring(question + 1) : url.substring(question + 1, fragment);
        for (String name : List.of("q", "query", "keyword", "keywords", "wd", "word", "p")) {
            String value = queryParam(rawQuery, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isKnownSearchEngineUrl(String url) {
        return isKnownSearchEngineHost(hostOf(url));
    }

    private boolean isKnownSearchEngineHost(String host) {
        String normalized = normalizedSearchHost(host);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.equals("bing.com")
            || normalized.endsWith(".bing.com")
            || normalized.equals("duckduckgo.com")
            || normalized.endsWith(".duckduckgo.com")
            || normalized.equals("google.com")
            || normalized.endsWith(".google.com")
            || normalized.equals("baidu.com")
            || normalized.endsWith(".baidu.com")
            || normalized.equals("sogou.com")
            || normalized.endsWith(".sogou.com")
            || normalized.equals("so.com")
            || normalized.endsWith(".so.com");
    }

    private List<Map<String, Object>> primaryResults(List<Map<String, Object>> fetchedResults,
                                                     WebSearchQueryIntent queryIntent) {
        if (fetchedResults == null || fetchedResults.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        String targetHost = queryIntent == null ? null : queryIntent.targetHost();
        for (Map<String, Object> result : fetchedResults) {
            Map<String, Object> copy = new LinkedHashMap<>(result);
            copy.putIfAbsent("source", "primary");
            if (targetHost != null && !targetHost.isBlank()) {
                copy.put("targetDomainMatched", sameSearchDomain(stringValue(copy.get("url")), targetHost));
            }
            results.add(copy);
        }
        return results;
    }

    private List<Map<String, Object>> siteSearchSeeds(List<Map<String, Object>> primaryResults,
                                                      WebSearchQueryIntent queryIntent) {
        if (primaryResults == null || primaryResults.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(10, primaryResults.size()));
        return primaryResults.stream()
            .limit(limit)
            .map(result -> {
                Map<String, Object> seed = new LinkedHashMap<>(result);
                seed.putIfAbsent("seedSource", "primary_result");
                return seed;
            })
            .toList();
    }

    private SearchStrategy searchStrategy(WebSearchQueryIntent queryIntent) {
        String original = queryIntent == null ? "" : firstNonBlank(queryIntent.originalQuery(), "");
        String searchQuery = queryIntent == null ? original : firstNonBlank(queryIntent.searchQuery(), original);
        String value = (original + " " + searchQuery).toLowerCase(Locale.ROOT);
        Set<String> modes = new LinkedHashSet<>();
        if (containsAny(value, List.of("latest", "today", "breaking", "news", "2026", "\u6700\u65b0", "\u4eca\u5929", "\u65b0\u95fb", "\u8d44\u8baf"))) {
            modes.add("news");
        }
        if (containsAny(value, List.of("finance", "earnings", "revenue", "stock", "securities", "annual report", "financial report", "\u8d22\u62a5", "\u4e1a\u7ee9", "\u80a1\u7968", "\u8bc1\u5238", "\u5e74\u62a5"))) {
            modes.add("finance");
        }
        if (containsAny(value, List.of("api", "sdk", "java", "python", "github", "docs", "documentation", "error", "stack", "\u6559\u7a0b", "\u4ee3\u7801", "\u6587\u6863"))) {
            modes.add("technical");
        }
        if (containsAny(value, List.of("how to", "tutorial", "guide", "example", "\u600e\u4e48", "\u5982\u4f55", "\u6307\u5357", "\u793a\u4f8b"))) {
            modes.add("tutorial");
        }
        if (containsAny(value, List.of("compare", "vs", "versus", "difference", "better", "\u5bf9\u6bd4", "\u533a\u522b", "\u54ea\u4e2a\u597d"))) {
            modes.add("comparison");
        }
        if (containsAny(value, List.of("price", "buy", "review", "rating", "product", "\u4ef7\u683c", "\u8d2d\u4e70", "\u8bc4\u6d4b", "\u5546\u54c1", "\u4ea7\u54c1"))) {
            modes.add("product");
        }
        if (containsAny(value, List.of("paper", "study", "research", "journal", "citation", "doi", "arxiv", "\u8bba\u6587", "\u7814\u7a76", "\u5b66\u672f"))) {
            modes.add("academic");
        }
        if (modes.isEmpty()) {
            modes.add("factual");
        }

        boolean freshnessSensitive = modes.contains("news")
            || containsAny(value, List.of("latest", "today", "2026", "\u6700\u65b0", "\u4eca\u5929", "\u8fd1\u671f"));
        boolean authoritativePreferred = modes.stream().anyMatch(mode -> Set.of("finance", "technical", "academic", "factual").contains(mode))
            || containsAny(value, List.of("official", "source", "\u5b98\u65b9", "\u6765\u6e90"));
        List<String> generatedQueries = generatedQueries(queryIntent, modes);
        return new SearchStrategy(modes.iterator().next(), new ArrayList<>(modes), freshnessSensitive, authoritativePreferred, generatedQueries);
    }

    private List<String> generatedQueries(WebSearchQueryIntent queryIntent, Set<String> modes) {
        String base = queryIntent == null ? "" : firstNonBlank(queryIntent.searchQuery(), queryIntent.originalQuery());
        if (base == null || base.isBlank()) {
            return List.of();
        }
        Set<String> queries = new LinkedHashSet<>();
        queries.add(base);
        if (queryIntent != null && queryIntent.targetHost() != null && !queryIntent.targetHost().isBlank()) {
            queries.add("site:" + queryIntent.targetHost() + " " + firstNonBlank(queryIntent.siteSearchQuery(), base));
        }
        if (modes.contains("finance")) {
            queries.add(base + " earnings revenue annual report");
            queries.add(base + " financial report official");
        }
        if (modes.contains("news")) {
            queries.add(base + " latest news");
        }
        if (modes.contains("technical")) {
            queries.add(base + " official docs github");
        }
        if (modes.contains("academic")) {
            queries.add(base + " paper doi research");
        }
        if (modes.contains("product")) {
            queries.add(base + " review price");
        }
        return new ArrayList<>(queries);
    }

    private Map<String, Object> queryPlan(WebSearchQueryIntent queryIntent, SearchStrategy strategy) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("original_query", queryIntent == null ? "" : firstNonBlank(queryIntent.originalQuery(), ""));
        plan.put("search_query", queryIntent == null ? "" : firstNonBlank(queryIntent.searchQuery(), ""));
        plan.put("site_search_query", queryIntent == null ? "" : firstNonBlank(queryIntent.siteSearchQuery(), ""));
        plan.put("target_site", queryIntent == null ? "" : firstNonBlank(queryIntent.targetHost(), ""));
        plan.put("strategy", strategy == null ? Map.of() : strategy.toMap());
        plan.put("generated_queries", strategy == null ? List.of() : strategy.generatedQueries());
        plan.put("recall_layers", List.of("primary_search", "targeted_site_search", "light_page_fetch"));
        plan.put("ranking_features", List.of("term_match", "page_evidence", "domain_trust", "freshness", "source_type_fit"));
        return plan;
    }

    private List<Map<String, Object>> fallbackExtractAllLinks(Document document, String query, int maxLinks) {
        if (document == null || maxLinks <= 0) {
            return List.of();
        }
        Element scope = first(
            document.selectFirst("main"),
            document.selectFirst("[role=main]"),
            document.selectFirst("ol"),
            document.body()
        );
        if (scope == null) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : scope.select("a[href]")) {
            if (results.size() >= maxLinks) {
                break;
            }
            String url = normalizeSearchUrl(firstNonBlank(link.absUrl("href"), link.attr("href")));
            if (!isSupportedResultUrl(url) || !seen.add(normalizeComparableUrl(url))) {
                continue;
            }
            String title = firstNonBlank(link.text(), url);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", results.size() + 1);
            item.put("title", title);
            item.put("url", url);
            item.put("snippet", nearbyText(link, query));
            item.put("source", "fallback_link");
            item.put("parserFallback", true);
            results.add(item);
        }
        return results;
    }

    private List<Map<String, Object>> rankSearchResults(List<Map<String, Object>> candidates,
                                                        List<Map<String, Object>> pageEvidence,
                                                        String query,
                                                        int limit,
                                                        SearchStrategy strategy) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, String> evidenceByUrl = new HashMap<>();
        if (pageEvidence != null) {
            for (Map<String, Object> evidence : pageEvidence) {
                String url = normalizeComparableUrl(stringValue(evidence.get("url")));
                String excerpt = stringValue(evidence.get("excerpt"));
                if (!url.isBlank() && excerpt != null && !excerpt.isBlank()) {
                    evidenceByUrl.put(url, excerpt);
                }
            }
        }
        List<String> terms = queryTerms(query);
        List<Map<String, Object>> ranked = new ArrayList<>();
        int originalIndex = 0;
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> copy = new LinkedHashMap<>(candidate);
            double score = relevanceScore(copy, evidenceByUrl, terms, originalIndex++, strategy);
            copy.put("relevanceScore", score);
            ranked.add(copy);
        }
        ranked.sort((left, right) -> {
            int scoreComparison = Double.compare(
                ((Number) right.getOrDefault("relevanceScore", 0)).doubleValue(),
                ((Number) left.getOrDefault("relevanceScore", 0)).doubleValue()
            );
            if (scoreComparison != 0) {
                return scoreComparison;
            }
            return Integer.compare(
                ((Number) left.getOrDefault("rank", Integer.MAX_VALUE)).intValue(),
                ((Number) right.getOrDefault("rank", Integer.MAX_VALUE)).intValue()
            );
        });
        int rank = 1;
        for (Map<String, Object> result : ranked) {
            result.put("rank", rank++);
        }
        return ranked.size() <= limit ? ranked : new ArrayList<>(ranked.subList(0, limit));
    }

    private double relevanceScore(Map<String, Object> candidate,
                                  Map<String, String> evidenceByUrl,
                                  List<String> terms,
                                  int originalIndex,
                                  SearchStrategy strategy) {
        String title = firstNonBlank(stringValue(candidate.get("title")), "");
        String snippet = firstNonBlank(stringValue(candidate.get("snippet")), "");
        String url = firstNonBlank(stringValue(candidate.get("url")), "");
        String evidence = evidenceByUrl.getOrDefault(normalizeComparableUrl(url), "");
        double score = Math.max(0, 100 - originalIndex);
        score += "site_search".equals(stringValue(candidate.get("source"))) ? 8 : 0;
        score += Boolean.TRUE.equals(candidate.get("parserFallback")) ? -10 : 0;
        for (String term : terms) {
            String normalizedTerm = term.toLowerCase(Locale.ROOT);
            if (title.toLowerCase(Locale.ROOT).contains(normalizedTerm)) {
                score += 10;
            }
            if (snippet.toLowerCase(Locale.ROOT).contains(normalizedTerm)) {
                score += 4;
            }
            if (url.toLowerCase(Locale.ROOT).contains(normalizedTerm)) {
                score += 2;
            }
            if (evidence.toLowerCase(Locale.ROOT).contains(normalizedTerm)) {
                score += 5;
            }
        }
        if (!evidence.isBlank()) {
            score += 3;
        }
        score += domainTrustScore(url) * 18;
        score += freshnessScore(candidate, evidence, strategy) * 14;
        score += sourceTypeFitScore(classifySourceType(url, title, stringValue(candidate.get("source"))), strategy) * 12;
        if (isDocumentUrl(url) && strategy != null && strategy.modes().stream().anyMatch(Set.of("technical", "academic", "finance")::contains)) {
            score += 5;
        }
        return score;
    }

    private String cleanupSiteKeyword(String query) {
        String value = query == null ? "" : query.trim();
        value = HTTP_URL_PATTERN.matcher(value).replaceAll(" ");
        value = SITE_OPERATOR_PATTERN.matcher(value).replaceAll(" ");
        value = value.replaceAll("(?i)\\b(from|within|inside|website|site|search|find|lookup|look up|on|in)\\b", " ");
        for (String phrase : List.of(
            "\u4ece\u8fd9\u4e2a\u7f51\u7ad9\u627e",
            "\u4ece\u8fd9\u4e2a\u7f51\u7ad9\u641c\u7d22",
            "\u8fd9\u4e2a\u7f51\u7ad9",
            "\u7f51\u7ad9",
            "\u5b98\u65b9\u7f51\u7ad9",
            "\u5b98\u7f51",
            "\u5b98\u65b9",
            "\u641c\u7d22",
            "\u67e5\u627e",
            "\u67e5\u8be2",
            "\u83b7\u53d6",
            "\u4ece"
        )) {
            value = value.replace(phrase, " ");
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private NaturalTargetSite naturalTargetSite(String query) {
        String value = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (containsAny(value, List.of(
            "\u4e0a\u6d77\u8bc1\u5238\u4ea4\u6613\u6240",
            "\u4e0a\u6d77\u4ea4\u6613\u6240",
            "\u4e0a\u4ea4\u6240",
            "sse.com.cn",
            " sse "
        ))) {
            return new NaturalTargetSite("sse.com.cn", "https://www.sse.com.cn/");
        }
        if (containsAny(value, List.of(
            "\u6df1\u5733\u8bc1\u5238\u4ea4\u6613\u6240",
            "\u6df1\u5733\u4ea4\u6613\u6240",
            "\u6df1\u4ea4\u6240",
            "szse.cn",
            " szse "
        ))) {
            return new NaturalTargetSite("szse.cn", "https://www.szse.cn/");
        }
        if (containsAny(value, List.of(
            "\u5de8\u6f6e\u8d44\u8baf",
            "\u5de8\u6f6e\u7f51",
            "cninfo.com.cn",
            " cninfo "
        ))) {
            return new NaturalTargetSite("cninfo.com.cn", "https://www.cninfo.com.cn/");
        }
        return null;
    }

    private String cleanupNaturalTargetSitePhrases(String query, String targetHost) {
        if (query == null || query.isBlank() || targetHost == null || targetHost.isBlank()) {
            return query == null ? "" : query.trim();
        }
        String value = query;
        if ("sse.com.cn".equals(targetHost)) {
            for (String phrase : List.of(
                "\u4e0a\u6d77\u8bc1\u5238\u4ea4\u6613\u6240\u7f51\u7ad9",
                "\u4e0a\u6d77\u8bc1\u5238\u4ea4\u6613\u6240",
                "\u4e0a\u6d77\u4ea4\u6613\u6240\u7f51\u7ad9",
                "\u4e0a\u6d77\u4ea4\u6613\u6240",
                "\u4e0a\u4ea4\u6240\u7f51\u7ad9",
                "\u4e0a\u4ea4\u6240",
                "sse.com.cn"
            )) {
                value = value.replace(phrase, " ");
            }
        } else if ("szse.cn".equals(targetHost)) {
            for (String phrase : List.of(
                "\u6df1\u5733\u8bc1\u5238\u4ea4\u6613\u6240\u7f51\u7ad9",
                "\u6df1\u5733\u8bc1\u5238\u4ea4\u6613\u6240",
                "\u6df1\u5733\u4ea4\u6613\u6240\u7f51\u7ad9",
                "\u6df1\u5733\u4ea4\u6613\u6240",
                "\u6df1\u4ea4\u6240\u7f51\u7ad9",
                "\u6df1\u4ea4\u6240",
                "szse.cn"
            )) {
                value = value.replace(phrase, " ");
            }
        } else if ("cninfo.com.cn".equals(targetHost)) {
            for (String phrase : List.of(
                "\u5de8\u6f6e\u8d44\u8baf\u7f51\u7ad9",
                "\u5de8\u6f6e\u8d44\u8baf",
                "\u5de8\u6f6e\u7f51",
                "cninfo.com.cn"
            )) {
                value = value.replace(phrase, " ");
            }
        }
        return value.replaceAll("\\s+", " ").trim();
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
        String host = normalizedSearchHost(hostOf(url));
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

    private String contentMode(List<Map<String, Object>> pageExcerpts, List<Map<String, Object>> siteSearchResults) {
        if (pageExcerpts != null && !pageExcerpts.isEmpty()) {
            return siteSearchResults != null && !siteSearchResults.isEmpty()
                ? "page_and_site_search_enriched"
                : "page_enriched";
        }
        return siteSearchResults != null && !siteSearchResults.isEmpty()
            ? "site_search_enriched"
            : "search_snippets_only";
    }

    private String normalizeSearchStage(String value) {
        if (value == null || value.isBlank()) {
            return SEARCH_STAGE_FULL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "primary", "snippet", "snippets", "search_only" -> SEARCH_STAGE_PRIMARY;
            case "site_search", "secondary", "secondary_search" -> SEARCH_STAGE_SITE_SEARCH;
            case "full", "legacy" -> SEARCH_STAGE_FULL;
            default -> SEARCH_STAGE_FULL;
        };
    }

    private boolean includeSiteSearch(ToolInput input, String searchStage) {
        if (SEARCH_STAGE_PRIMARY.equals(searchStage)) {
            return input.getParameterAsBoolean("include_site_search", false);
        }
        return input.getParameterAsBoolean("include_site_search", true);
    }

    private boolean includePageFetch(ToolInput input, String searchStage) {
        if (SEARCH_STAGE_PRIMARY.equals(searchStage)) {
            return input.getParameterAsBoolean("fetch_pages", false);
        }
        return input.getParameterAsBoolean("fetch_pages", properties.isFetchPages());
    }

    private String webSearchMode(ToolInput input) {
        String explicit = firstNonBlank(
            input == null ? null : input.getParameterAsString("web_search_mode", ""),
            input == null ? null : input.getParameterAsString("mode", "")
        );
        String value = firstNonBlank(explicit, properties.getDefaultMode());
        if (value == null || value.isBlank()) {
            return SITE_SEARCH_MODE_JAVA;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "browser", "playwright", "render", "rendered" -> SITE_SEARCH_MODE_BROWSER;
            case "auto" -> SITE_SEARCH_MODE_AUTO;
            case "http", "jsoup", "java", "java_http", "default" -> SITE_SEARCH_MODE_JAVA;
            default -> SITE_SEARCH_MODE_JAVA;
        };
    }

    private boolean webSearchBrowserFirst(String webSearchMode) {
        return modeBrowserFirst(webSearchMode);
    }

    private boolean webSearchFallbackToJava() {
        return properties.isBrowserFallbackToJava();
    }

    private String siteSearchMode(ToolInput input) {
        String explicit = firstNonBlank(
            input == null ? null : input.getParameterAsString("site_search_mode", ""),
            input == null ? null : input.getParameterAsString("mode", "")
        );
        String configured = properties.getSiteSearch() == null
            ? SITE_SEARCH_MODE_JAVA
            : properties.getSiteSearch().getDefaultMode();
        String value = firstNonBlank(explicit, configured);
        if (value == null || value.isBlank()) {
            return SITE_SEARCH_MODE_JAVA;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "browser", "playwright", "render", "rendered" -> SITE_SEARCH_MODE_BROWSER;
            case "auto" -> SITE_SEARCH_MODE_AUTO;
            case "http", "jsoup", "java", "java_http", "default" -> SITE_SEARCH_MODE_JAVA;
            default -> SITE_SEARCH_MODE_JAVA;
        };
    }

    private boolean siteSearchBrowserFirst(String siteSearchMode) {
        return modeBrowserFirst(siteSearchMode);
    }

    private boolean modeBrowserFirst(String mode) {
        return (SITE_SEARCH_MODE_BROWSER.equals(mode) || SITE_SEARCH_MODE_AUTO.equals(mode))
            && browserRenderingEnabled();
    }

    private boolean siteSearchFallbackToJava() {
        return properties.getSiteSearch() == null || properties.getSiteSearch().isBrowserFallbackToJava();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> seedResults(ToolInput input, WebSearchQueryIntent queryIntent) {
        List<Map<String, Object>> results = new ArrayList<>();
        Object candidates = firstObject(
            input.getParameter("candidate_results"),
            input.getParameter("selected_results"),
            input.getParameter("results")
        );
        if (candidates instanceof List<?> list) {
            int fallbackRank = 1;
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    raw.forEach((key, value) -> {
                        if (key != null) {
                            result.put(String.valueOf(key), value);
                        }
                    });
                    if (!result.containsKey("rank")) {
                        result.put("rank", fallbackRank);
                    }
                    fallbackRank++;
                    if (isHttpUrl(stringValue(result.get("url")))) {
                        results.add(result);
                    }
                } else {
                    String url = stringValue(item);
                    if (isHttpUrl(url)) {
                        results.add(seedResult(results.size() + 1, url));
                    }
                }
            }
        }
        for (String url : stringList(firstObject(
            input.getParameter("seed_urls"),
            input.getParameter("selected_urls"),
            input.getParameter("urls")
        ))) {
            if (isHttpUrl(url)) {
                results.add(seedResult(results.size() + 1, url));
            }
        }
        String targetUrl = firstNonBlank(
            input.getParameterAsString("target_url", ""),
            queryIntent == null ? null : queryIntent.targetUrl()
        );
        if (isHttpUrl(targetUrl)) {
            results.add(seedResult(results.size() + 1, targetUrl));
        }
        Map<String, Map<String, Object>> byUrl = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            String url = stringValue(result.get("url"));
            if (url != null && !url.isBlank()) {
                byUrl.putIfAbsent(normalizeComparableUrl(url), result);
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    private Map<String, Object> seedResult(int rank, String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rank", rank);
        result.put("title", url);
        result.put("url", url);
        result.put("snippet", "");
        result.put("source", "llm_selected_seed");
        return result;
    }

    private SearchResultRelevance assessSearchResultRelevance(List<Map<String, Object>> results, String query) {
        int total = results == null ? 0 : results.size();
        return new SearchResultRelevance(total > 0, total);
    }

    private String structuredSearchText(List<Map<String, Object>> results,
                                        List<Map<String, Object>> pageExcerpts,
                                        SearchResultRelevance relevance) {
        StringBuilder builder = new StringBuilder();
        builder.append("results_returned_for_model_judgment: ").append(relevance.totalResults()).append('\n');
        builder.append("results:\n");
        if (results != null) {
            for (Map<String, Object> result : results) {
                builder.append("- rank: ").append(result.get("rank")).append('\n');
                builder.append("  title: ").append(firstNonBlank(stringValue(result.get("title")), "")).append('\n');
                builder.append("  url: ").append(firstNonBlank(stringValue(result.get("url")), "")).append('\n');
                builder.append("  source: ").append(firstNonBlank(stringValue(result.get("source")), "search_result")).append('\n');
                String snippet = firstNonBlank(stringValue(result.get("snippet")), "");
                if (!snippet.isBlank()) {
                    builder.append("  snippet: ").append(snippet.replaceAll("\\s+", " ").trim()).append('\n');
                }
                String pageExcerpt = firstNonBlank(stringValue(result.get("pageExcerpt")), "");
                if (!pageExcerpt.isBlank()) {
                    builder.append("  pageExcerpt: ").append(pageExcerpt.replaceAll("\\s+", " ").trim()).append('\n');
                }
                Map<String, Object> evidence = mapValue(result.get("urlEvidence"));
                if (!evidence.isEmpty()) {
                    builder.append("  domain: ").append(firstNonBlank(stringValue(evidence.get("domain")), "")).append('\n');
                    builder.append("  status: ").append(firstNonBlank(stringValue(evidence.get("status")), "unknown")).append('\n');
                    builder.append("  content_type: ").append(firstNonBlank(stringValue(evidence.get("content_type")), "other")).append('\n');
                    builder.append("  source_type: ").append(firstNonBlank(stringValue(evidence.get("source_type")), "other")).append('\n');
                    builder.append("  trust_score: ").append(firstNonBlank(stringValue(evidence.get("trust_score")), "0")).append('\n');
                    builder.append("  freshness_score: ").append(firstNonBlank(stringValue(evidence.get("freshness_score")), "0")).append('\n');
                    String summary = firstNonBlank(stringValue(evidence.get("summary")), "");
                    if (!summary.isBlank()) {
                        builder.append("  summary: ").append(summary.replaceAll("\\s+", " ").trim()).append('\n');
                    }
                    Object keyPoints = evidence.get("key_points");
                    if (keyPoints instanceof List<?> list && !list.isEmpty()) {
                        builder.append("  key_points: ").append(list).append('\n');
                    }
                }
            }
        }
        if (pageExcerpts != null && !pageExcerpts.isEmpty()) {
            builder.append("page_excerpts:\n");
            for (Map<String, Object> excerpt : pageExcerpts) {
                builder.append("- title: ").append(firstNonBlank(stringValue(excerpt.get("title")), "")).append('\n');
                builder.append("  url: ").append(firstNonBlank(stringValue(excerpt.get("url")), "")).append('\n');
                builder.append("  excerpt: ")
                    .append(firstNonBlank(stringValue(excerpt.get("excerpt")), "").replaceAll("\\s+", " ").trim())
                    .append('\n');
            }
        }
        return builder.toString().trim();
    }

    private List<Map<String, Object>> crawlCandidates(List<Map<String, Object>> results, int limit) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        int maxCandidates = Math.max(1, Math.min(limit, properties.getMaxResults()));
        List<Map<String, Object>> candidates = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (Map<String, Object> result : results) {
            if (candidates.size() >= maxCandidates) {
                break;
            }
            String url = stringValue(result.get("url"));
            if (url == null || url.isBlank() || !seenUrls.add(normalizeComparableUrl(url))) {
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("rank", result.get("rank"));
            candidate.put("url", url);
            candidate.put("title", compactText(firstNonBlank(stringValue(result.get("title")), url), 160));
            candidate.put("retrieval_snippet", compactText(firstNonBlank(stringValue(result.get("snippet")), ""), 420));
            Map<String, Object> evidence = mapValue(result.get("urlEvidence"));
            if (!evidence.isEmpty()) {
                candidate.put("summary", compactText(stringValue(evidence.get("summary")), properties.getUrlEvidence().getMaxSummaryChars()));
                candidate.put("domain", evidence.get("domain"));
                candidate.put("status", evidence.get("status"));
                candidate.put("language", evidence.get("language"));
                candidate.put("content_type", evidence.get("content_type"));
                candidate.put("source_type", evidence.get("source_type"));
                candidate.put("freshness", evidence.get("freshness"));
                candidate.put("keywords", evidence.get("keywords"));
                candidate.put("relevance_score", evidence.get("relevance_score"));
                candidate.put("trust_score", evidence.get("trust_score"));
                candidate.put("freshness_score", evidence.get("freshness_score"));
                candidate.put("key_points", evidence.get("key_points"));
                candidate.put("evidence_card", evidence);
            }
            candidate.put("source", firstNonBlank(stringValue(result.get("source")), "site_search"));
            copyIfPresent(result, candidate, "publishedAt");
            copyIfPresent(result, candidate, "securityCode");
            copyIfPresent(result, candidate, "securityName");
            copyIfPresent(result, candidate, "documentId");
            copyIfPresent(result, candidate, "siteSearchUrl");
            candidate.put("crawl_decision", "model_should_decide");
            candidates.add(candidate);
        }
        return candidates;
    }

    private String siteSearchModelSelectionText(String query, List<Map<String, Object>> crawlCandidates) {
        if (crawlCandidates == null || crawlCandidates.isEmpty()) {
            return "No site-search candidate URLs were found. Do not call web_crawler unless another reliable URL is available.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Site-search returned candidate URLs only. Review relevance, then call web_crawler only for URLs needed to answer the user.");
        if (query != null && !query.isBlank()) {
            builder.append("\nquery: ").append(compactText(query, 160));
        }
        builder.append("\ncandidates:\n");
        for (Map<String, Object> candidate : crawlCandidates) {
            builder.append("- rank: ").append(candidate.get("rank")).append('\n');
            builder.append("  url: ").append(firstNonBlank(stringValue(candidate.get("url")), "")).append('\n');
            builder.append("  title: ").append(firstNonBlank(stringValue(candidate.get("title")), "")).append('\n');
            String snippet = firstNonBlank(stringValue(candidate.get("retrieval_snippet")), "");
            if (!snippet.isBlank()) {
                builder.append("  retrieval_snippet: ").append(snippet).append('\n');
            }
            String summary = firstNonBlank(stringValue(candidate.get("summary")), "");
            if (!summary.isBlank()) {
                builder.append("  summary: ").append(summary).append('\n');
            }
            String status = firstNonBlank(stringValue(candidate.get("status")), "");
            if (!status.isBlank()) {
                builder.append("  status: ").append(status).append('\n');
            }
            String contentType = firstNonBlank(stringValue(candidate.get("content_type")), "");
            if (!contentType.isBlank()) {
                builder.append("  content_type: ").append(contentType).append('\n');
            }
            String sourceType = firstNonBlank(stringValue(candidate.get("source_type")), "");
            if (!sourceType.isBlank()) {
                builder.append("  source_type: ").append(sourceType).append('\n');
            }
            Object keyPoints = candidate.get("key_points");
            if (keyPoints instanceof List<?> list && !list.isEmpty()) {
                builder.append("  key_points: ").append(list).append('\n');
            }
            String source = firstNonBlank(stringValue(candidate.get("source")), "");
            if (!source.isBlank()) {
                builder.append("  source: ").append(source).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private String compactText(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    private record WebSearchQueryIntent(
        String originalQuery,
        String searchQuery,
        String siteSearchQuery,
        String targetUrl,
        String targetHost
    ) {
    }

    private record SearchStrategy(String primaryMode,
                                  List<String> modes,
                                  boolean freshnessSensitive,
                                  boolean authoritativePreferred,
                                  List<String> generatedQueries) {

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("primary_mode", primaryMode);
            values.put("modes", modes == null ? List.of() : modes);
            values.put("freshness_sensitive", freshnessSensitive);
            values.put("authoritative_preferred", authoritativePreferred);
            values.put("generated_query_count", generatedQueries == null ? 0 : generatedQueries.size());
            return values;
        }
    }

    private record NaturalTargetSite(String host, String url) {
    }

    private record SearchResultRelevance(boolean useful,
                                         int totalResults) {

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("returnedForModelJudgment", useful);
            values.put("totalResults", totalResults);
            return values;
        }
    }

    private HtmlResponse sendSearchEngineRequest(String provider,
                                                 String endpoint,
                                                 String searchQuery,
                                                 WebSearchRequestContext context,
                                                 List<Map<String, Object>> networkAudit,
                                                 String webSearchMode) throws Exception {
        if ("bing_html".equals(provider)) {
            return sendBingSearchRequest(endpoint, searchQuery, context, networkAudit, webSearchMode);
        }
        return sendSearchPageRequestWithMode(
            endpoint,
            Map.of("q", searchQuery),
            searchQuery,
            "search",
            context,
            networkAudit,
            "GET",
            webSearchMode,
            webSearchFallbackToJava(),
            "web_search"
        );
    }

    private HtmlResponse sendBingSearchRequest(String endpoint,
                                               String searchQuery,
                                               WebSearchRequestContext context,
                                               List<Map<String, Object>> networkAudit,
                                               String webSearchMode) throws Exception {
        if (isBingHost(endpoint)) {
            return sendBingFormDrivenSearchRequest(endpoint, searchQuery, context, networkAudit, webSearchMode);
        }
        HtmlResponse landingResponse = sendSearchPageRequestWithMode(
            endpoint,
            Map.of(),
            searchQuery,
            "search_open",
            context,
            networkAudit,
            "GET",
            webSearchMode,
            webSearchFallbackToJava(),
            "web_search"
        );
        SiteSearchForm searchForm = firstSearchForm(landingResponse.document(), endpoint, searchQuery);
        if (searchForm == null) {
            if (hasQueryParameter(endpoint, "q") && looksLikeBingResultPage(landingResponse.document())) {
                log.info("Bing endpoint already returned a search result page endpoint={}", endpoint);
                return landingResponse;
            }
            log.warn("Bing search form was not found after opening endpoint={}, falling back to q parameter", endpoint);
            return sendSearchPageRequestWithMode(
                urlWithQueryParams(endpoint, bingSearchQueryParams(searchQuery)),
                Map.of(),
                searchQuery,
                "search",
                context,
                networkAudit,
                "GET",
                webSearchMode,
                webSearchFallbackToJava(),
                "web_search"
            );
        }
        String submittedUrl = "POST".equals(searchForm.method())
            ? searchForm.submittedUrl()
            : urlWithRawQueryParams(searchForm.actionUrl(), searchForm.parameters());
        log.info("Bing search form found endpoint={} actionUrl={} method={} submittedUrl={}",
            endpoint,
            searchForm.actionUrl(),
            searchForm.method(),
            submittedUrl);
        if (!"POST".equals(searchForm.method())) {
            return sendSearchPageRequestWithMode(
                urlWithQueryParams(searchForm.actionUrl(), searchForm.parameters()),
                Map.of(),
                searchQuery,
                "search_submit",
                contextWithReferer(context, endpoint, searchQuery),
                networkAudit,
                "GET",
                webSearchMode,
                webSearchFallbackToJava(),
                "web_search"
            );
        }
        return sendSearchPageRequestWithMode(
            searchForm.actionUrl(),
            searchForm.parameters(),
            searchQuery,
            "search_submit",
            contextWithReferer(context, endpoint, searchQuery),
            networkAudit,
            searchForm.method(),
            webSearchMode,
            webSearchFallbackToJava(),
            "web_search"
        );
    }

    private HtmlResponse sendBingFormDrivenSearchRequest(String endpoint,
                                                         String searchQuery,
                                                         WebSearchRequestContext context,
                                                         List<Map<String, Object>> networkAudit,
                                                         String webSearchMode) throws Exception {
        HtmlResponse browserResponse = submitBingSearchWithInteractiveBrowser(
            endpoint,
            searchQuery,
            context,
            networkAudit
        );
        if (browserResponse != null) {
            return browserResponse;
        }
        HtmlResponse submittedResponse = submitBingSearchFormIfPossible(
            endpoint,
            searchQuery,
            context,
            networkAudit,
            webSearchMode
        );
        if (submittedResponse != null) {
            return submittedResponse;
        }
        String fallbackUrl = urlWithQueryParams(bingSearchLandingUrl(endpoint), bingSearchQueryParams(searchQuery));
        log.warn("Bing form flow failed endpoint={}, falling back to direct query url={}", endpoint, fallbackUrl);
        return sendSearchPageRequestWithMode(
            fallbackUrl,
            Map.of(),
            searchQuery,
            "search",
            context,
            networkAudit,
            "GET",
            webSearchMode,
            webSearchFallbackToJava(),
            "web_search"
        );
    }

    private HtmlResponse submitBingSearchWithInteractiveBrowser(String endpoint,
                                                                String searchQuery,
                                                                WebSearchRequestContext context,
                                                                List<Map<String, Object>> networkAudit) throws InterruptedException {
        if (!browserRenderingEnabled()) {
            return null;
        }
        String landingUrl = bingSearchLandingUrl(endpoint);
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            WebSearchToolProperties.ProxyConfig proxy = chooseProxy(context, attempt);
            long startedAt = System.currentTimeMillis();
            boolean rateAcquired = false;
            try {
                rateAcquired = acquireRateSlot();
                HtmlResponse response = executeBingInteractivePlaywrightSearch(landingUrl, searchQuery, context, proxy);
                long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                addAudit(networkAudit, searchQuery, landingUrl, "search_browser", proxyId(proxy), response.statusCode(),
                    durationMs, "OK", "playwright interactive bing search");
                logWebSearchAudit(searchQuery, landingUrl, "search_browser", proxy, response.statusCode(), durationMs, null);
                markProxySuccess(proxy);
                return response;
            } catch (Exception ex) {
                lastException = ex;
                markProxyFailure(proxy);
                long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                addAudit(networkAudit, searchQuery, landingUrl, "search_browser", proxyId(proxy), 0,
                    durationMs, "FAILED", ex.getMessage());
                logWebSearchAudit(searchQuery, landingUrl, "search_browser", proxy, 0, durationMs, ex.getMessage());
                log.warn("Bing interactive browser search failed attempt={}/{} landingUrl={} error={}",
                    attempt,
                    maxAttempts,
                    landingUrl,
                    ex.getMessage());
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            } finally {
                if (rateAcquired) {
                    rateSemaphore.release();
                }
            }
        }
        if (lastException != null) {
            log.warn("Bing interactive browser search exhausted attempts landingUrl={} error={}",
                landingUrl,
                lastException.getMessage());
        }
        return null;
    }

    private HtmlResponse executeBingInteractivePlaywrightSearch(String landingUrl,
                                                               String searchQuery,
                                                               WebSearchRequestContext context,
                                                               WebSearchToolProperties.ProxyConfig proxy) {
        if (persistentBingBrowserEnabled(proxy)) {
            return persistentBingBrowserSession.search(landingUrl, searchQuery, context);
        }
        WebSearchToolProperties.BrowserProperties browserProperties = properties.getBrowser();
        int timeoutMs = interactiveBrowserTimeoutMs(browserProperties);
        var launchOptions = playwrightSupport.headlessLaunchOptions(timeoutMs, playwrightProxyConfig(proxy));
        try (Playwright playwright = playwrightSupport.createPlaywright(playwrightBrowserConfig(browserProperties));
             Browser browser = playwright.chromium().launch(launchOptions);
             BrowserContext browserContext = browser.newContext(playwrightContextOptions(context))) {
            browserContext.route("**/*", route -> {
                String resourceType = route.request().resourceType();
                if (Set.of("image", "media", "font").contains(resourceType)) {
                    route.abort();
                } else {
                    route.resume();
                }
            });
            Page page = browserContext.newPage();
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);

            com.microsoft.playwright.Response response = null;
            RuntimeException navigationError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    response = page.navigate(landingUrl, new Page.NavigateOptions()
                        .setTimeout(timeoutMs)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    navigationError = null;
                    break;
                } catch (RuntimeException ex) {
                    navigationError = ex;
                    if (attempt >= 3) {
                        throw ex;
                    }
                }
            }
            if (navigationError != null) {
                throw navigationError;
            }

            String querySelector = "input[name='q'], textarea[name='q']";
            page.waitForSelector(querySelector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            page.fill(querySelector, searchQuery);
            page.keyboard().press("Enter");
            page.waitForSelector("li.b_algo", new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(Math.min(timeoutMs, 5000)));
            } catch (RuntimeException ex) {
                log.debug("Bing result page DOMContentLoaded wait ended early: {}", ex.getMessage());
            }

            String html = page.content();
            if (html == null || html.isBlank()) {
                throw new IllegalStateException("Playwright returned empty Bing result page");
            }
            Document document = Jsoup.parse(html, page.url());
            if (!looksLikeBingResultPage(document)) {
                throw new IllegalStateException("Bing result selector li.b_algo was not present after search");
            }
            return new HtmlResponse(response == null ? 200 : response.status(), document);
        }
    }

    private boolean persistentBingBrowserEnabled(WebSearchToolProperties.ProxyConfig proxy) {
        WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
        return proxy == null
            && browser != null
            && browser.isEnabled()
            && browser.isPersistentEnabled();
    }

    private int interactiveBrowserTimeoutMs(WebSearchToolProperties.BrowserProperties browserProperties) {
        int browserTimeout = browserProperties == null ? 0 : browserProperties.getNavigationTimeoutMs();
        if (browserTimeout > 0) {
            return browserTimeout;
        }
        int requestTimeout = Math.max(0, properties.getTimeoutMs());
        return requestTimeout > 0 ? requestTimeout : 60000;
    }

    private final class PersistentBingBrowserSession {

        private Playwright playwright;
        private Browser browser;
        private BrowserContext browserContext;
        private Page page;
        private String landingUrl;
        private String contextKey;

        private synchronized HtmlResponse search(String requestedLandingUrl,
                                                 String searchQuery,
                                                 WebSearchRequestContext context) {
            WebSearchToolProperties.BrowserProperties browserProperties = properties.getBrowser();
            int timeoutMs = interactiveBrowserTimeoutMs(browserProperties);
            String key = persistentContextKey(context);
            try {
                ensureReady(requestedLandingUrl, context, key, timeoutMs);
                String previousMarker = firstBingResultMarker();
                String querySelector = "input[name='q'], textarea[name='q']";
                page.waitForSelector(querySelector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
                page.fill(querySelector, searchQuery);
                page.keyboard().press("Enter");
                page.waitForSelector("li.b_algo", new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
                waitForFreshBingResults(previousMarker, timeoutMs);
                try {
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(Math.min(timeoutMs, 5000)));
                } catch (RuntimeException ex) {
                    log.debug("Persistent Bing page DOMContentLoaded wait ended early: {}", ex.getMessage());
                }
                String html = page.content();
                if (html == null || html.isBlank()) {
                    throw new IllegalStateException("persistent Playwright returned empty Bing result page");
                }
                Document document = Jsoup.parse(html, page.url());
                if (!looksLikeBingResultPage(document)) {
                    throw new IllegalStateException("persistent Bing result selector li.b_algo was not present after search");
                }
                return new HtmlResponse(200, document);
            } catch (RuntimeException ex) {
                close();
                throw ex;
            }
        }

        private void ensureReady(String requestedLandingUrl,
                                 WebSearchRequestContext context,
                                 String key,
                                 int timeoutMs) {
            boolean recreate = playwright == null
                || browser == null
                || !browser.isConnected()
                || browserContext == null
                || page == null
                || page.isClosed()
                || landingUrl == null
                || !landingUrl.equals(requestedLandingUrl)
                || contextKey == null
                || !contextKey.equals(key);
            if (!recreate) {
                return;
            }
            close();
            WebSearchToolProperties.BrowserProperties browserProperties = properties.getBrowser();
            var launchOptions = playwrightSupport.headlessLaunchOptions(timeoutMs, null);
            playwright = playwrightSupport.createPlaywright(playwrightBrowserConfig(browserProperties));
            browser = playwright.chromium().launch(launchOptions);
            browserContext = browser.newContext(playwrightContextOptions(context));
            browserContext.route("**/*", route -> {
                String resourceType = route.request().resourceType();
                if (Set.of("image", "media", "font").contains(resourceType)) {
                    route.abort();
                } else {
                    route.resume();
                }
            });
            page = browserContext.newPage();
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);
            page.navigate(requestedLandingUrl, new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForSelector("input[name='q'], textarea[name='q']",
                new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            landingUrl = requestedLandingUrl;
            contextKey = key;
            log.info("Persistent Bing browser session initialized landingUrl={}", requestedLandingUrl);
        }

        private String firstBingResultMarker() {
            if (page == null || page.isClosed()) {
                return "";
            }
            Object value = page.evaluate("""
                () => {
                    const item = document.querySelector('li.b_algo h2 a');
                    return item ? (item.textContent || '') + '|' + (item.href || '') : '';
                }
                """);
            return value == null ? "" : String.valueOf(value);
        }

        private void waitForFreshBingResults(String previousMarker, int timeoutMs) {
            if (previousMarker == null || previousMarker.isBlank()) {
                return;
            }
            try {
                page.waitForFunction("""
                    previous => {
                        const item = document.querySelector('li.b_algo h2 a');
                        if (!item) return false;
                        const marker = (item.textContent || '') + '|' + (item.href || '');
                        return marker && marker !== previous;
                    }
                    """,
                    previousMarker,
                    new Page.WaitForFunctionOptions().setTimeout(Math.min(timeoutMs, 10000)));
            } catch (RuntimeException ex) {
                log.debug("Persistent Bing result marker did not change before timeout: {}", ex.getMessage());
            }
        }

        private String persistentContextKey(WebSearchRequestContext context) {
            return firstNonBlank(context == null ? null : context.referer(), "");
        }

        private void close() {
            closeQuietly(page);
            page = null;
            closeQuietly(browserContext);
            browserContext = null;
            closeQuietly(browser);
            browser = null;
            closeQuietly(playwright);
            playwright = null;
            landingUrl = null;
            contextKey = null;
        }

        private void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception ex) {
                log.debug("Persistent Bing browser resource close ignored: {}", ex.getMessage());
            }
        }
    }

    private HtmlResponse submitBingSearchFormIfPossible(String endpoint,
                                                        String searchQuery,
                                                        WebSearchRequestContext context,
                                                        List<Map<String, Object>> networkAudit,
                                                        String webSearchMode) {
        String landingUrl = bingSearchLandingUrl(endpoint);
        try {
            HtmlResponse landingResponse = sendSearchPageRequestWithMode(
                landingUrl,
                Map.of(),
                searchQuery,
                "search_open",
                context,
                networkAudit,
                "GET",
                webSearchMode,
                webSearchFallbackToJava(),
                "web_search"
            );
            SiteSearchForm searchForm = firstSearchForm(landingResponse.document(), landingUrl, searchQuery);
            if (searchForm == null) {
                log.warn("Bing search form was not found after opening landingUrl={}", landingUrl);
                return null;
            }
            searchForm = bingSearchForm(searchForm, searchQuery);
            String submittedUrl = "POST".equals(searchForm.method())
                ? searchForm.submittedUrl()
                : urlWithQueryParams(searchForm.actionUrl(), searchForm.parameters());
            log.info("Bing landing search form found landingUrl={} actionUrl={} method={} submittedUrl={}",
                landingUrl,
                searchForm.actionUrl(),
                searchForm.method(),
                submittedUrl);
            if (!"POST".equals(searchForm.method())) {
                return sendSearchPageRequestWithMode(
                    submittedUrl,
                    Map.of(),
                    searchQuery,
                    "search_submit",
                    contextWithReferer(context, landingUrl, searchQuery),
                    networkAudit,
                    "GET",
                    webSearchMode,
                    webSearchFallbackToJava(),
                    "web_search"
                );
            }
            return sendSearchPageRequestWithMode(
                searchForm.actionUrl(),
                searchForm.parameters(),
                searchQuery,
                "search_submit",
                contextWithReferer(context, landingUrl, searchQuery),
                networkAudit,
                searchForm.method(),
                webSearchMode,
                webSearchFallbackToJava(),
                "web_search"
            );
        } catch (Exception ex) {
            log.warn("Bing search form submission failed landingUrl={} error={}", landingUrl, ex.getMessage());
            return null;
        }
    }

    private SiteSearchForm bingSearchForm(SiteSearchForm form, String searchQuery) {
        Map<String, String> parameters = new LinkedHashMap<>(form.parameters());
        parameters.putAll(bingSearchQueryParams(searchQuery));
        String submittedUrl = "POST".equals(form.method())
            ? form.actionUrl()
            : urlWithQueryParams(form.actionUrl(), parameters);
        return new SiteSearchForm(
            form.actionUrl(),
            parameters,
            form.method(),
            submittedUrl,
            form.sourcePageUrl()
        );
    }

    private String bingSearchLandingUrl(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = firstNonBlank(uri.getScheme(), "https");
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return endpoint;
            }
            int port = uri.getPort();
            StringBuilder builder = new StringBuilder(scheme).append("://").append(host);
            if (port > 0 && !(("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443))) {
                builder.append(':').append(port);
            }
            builder.append("/search");
            return builder.toString();
        } catch (Exception ex) {
            return "https://www.bing.com/search";
        }
    }

    private Map<String, String> bingSearchQueryParams(String searchQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("go", "\u641c\u7d22");
        params.put("q", searchQuery);
        params.put("qs", "n");
        params.put("form", "QBRE");
        params.put("sp", "-1");
        params.put("lq", "0");
        params.put("pq", "");
        params.put("sc", "0-0");
        params.put("sk", "");
        params.put("mkt", "zh-CN");
        params.put("setlang", "zh-Hans");
        params.put("cc", "CN");
        return params;
    }

    private boolean isBingHost(String url) {
        String host = hostOf(url);
        return host != null && (host.equalsIgnoreCase("bing.com") || host.toLowerCase(Locale.ROOT).endsWith(".bing.com"));
    }

    private SiteSearchForm firstSearchForm(Document document, String pageUrl, String query) {
        List<SiteSearchForm> forms = findSearchForms(document, pageUrl, query, false);
        return forms.isEmpty() ? null : forms.get(0);
    }

    private boolean looksLikeBingResultPage(Document document) {
        return document != null && document.selectFirst("li.b_algo h2 a[href]") != null;
    }

    private boolean hasQueryParameter(String url, String name) {
        if (url == null || name == null || name.isBlank()) {
            return false;
        }
        int question = url.indexOf('?');
        if (question < 0 || question == url.length() - 1) {
            return false;
        }
        int fragment = url.indexOf('#', question + 1);
        String query = fragment < 0 ? url.substring(question + 1) : url.substring(question + 1, fragment);
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (name.equalsIgnoreCase(key.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the search attempts.
     *
     * @param provider the provider value
     * @param endpoint the endpoint value
     * @return the built search attempts
     */
    private List<SearchAttempt> buildSearchAttempts(String provider, String endpoint) {
        List<SearchAttempt> attempts = new ArrayList<>();
        attempts.add(new SearchAttempt(provider, endpoint));
        if (!properties.isFallbackEnabled()) {
            return attempts;
        }
        if (!"bing_html".equals(provider)) {
            attempts.add(new SearchAttempt("bing_html", "https://www.bing.com/search"));
        }
        if (!"duckduckgo_html".equals(provider)) {
            attempts.add(new SearchAttempt("duckduckgo_html", "https://duckduckgo.com/html/"));
        }
        return attempts;
    }

    /**
     * Builds the search message.
     *
     * @param result the result value
     * @return the built search message
     */
    private String buildSearchMessage(Map<String, Object> result) {
        if (SEARCH_STAGE_SITE_SEARCH.equals(result.get("search_stage"))) {
            String selectionText = stringValue(result.get("model_selection_text"));
            if (selectionText != null && !selectionText.isBlank()) {
                return selectionText;
            }
        }
        Object referenceUrlsValue = result.get("reference_urls");
        if (!(referenceUrlsValue instanceof List<?> referenceUrls) || referenceUrls.isEmpty()) {
            return "Search completed successfully, but no reference URLs were found.";
        }
        StringBuilder message = new StringBuilder("Search completed successfully.\nTop 10 reference URLs:\n");
        int rank = 1;
        for (Object referenceUrl : referenceUrls) {
            if (referenceUrl instanceof String url && !url.isBlank()) {
                message.append(rank++).append(". ").append(url).append('\n');
            }
        }
        Object pageExcerptCount = result.get("page_excerpt_count");
        if (pageExcerptCount instanceof Number count && count.intValue() > 0) {
            message.append("\nFetched page excerpts: ").append(count.intValue());
        }
        return message.toString().trim();
    }

    private void attachUrlEvidence(List<Map<String, Object>> results,
                                   List<Map<String, Object>> pageEvidence,
                                   String query,
                                   SearchStrategy strategy) {
        if (!properties.getUrlEvidence().isEnabled() || results == null || results.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> evidenceByUrl = new HashMap<>();
        if (pageEvidence != null) {
            for (Map<String, Object> evidence : pageEvidence) {
                String url = stringValue(evidence.get("url"));
                if (url != null && !url.isBlank()) {
                    evidenceByUrl.put(normalizeComparableUrl(url), evidence);
                }
            }
        }
        for (Map<String, Object> result : results) {
            String url = stringValue(result.get("url"));
            Map<String, Object> fetchedEvidence = url == null
                ? Map.of()
                : evidenceByUrl.getOrDefault(normalizeComparableUrl(url), Map.of());
            Map<String, Object> evidence = buildUrlEvidenceCard(result, fetchedEvidence, query, strategy);
            result.put("urlEvidence", evidence);
            result.put("evidence_card", evidence);
            putIfNotBlank(result, "summary", stringValue(evidence.get("summary")));
            putIfNotBlank(result, "domain", stringValue(evidence.get("domain")));
            putIfNotBlank(result, "language", stringValue(evidence.get("language")));
            putIfNotBlank(result, "content_type", stringValue(evidence.get("content_type")));
            putIfNotBlank(result, "freshness", stringValue(evidence.get("freshness")));
            putIfNotBlank(result, "status", stringValue(evidence.get("status")));
            putIfNotBlank(result, "source_type", stringValue(evidence.get("source_type")));
            result.put("trust_score", evidence.get("trust_score"));
            result.put("freshness_score", evidence.get("freshness_score"));
            result.put("rerank_score", evidence.get("rerank_score"));
            result.put("key_points", evidence.getOrDefault("key_points", List.of()));
            result.put("quality_flags", evidence.getOrDefault("quality_flags", List.of()));
            result.put("keywords", evidence.getOrDefault("keywords", List.of()));
            result.put("relevance_score", evidence.get("relevance_score"));
        }
    }

    private Map<String, Object> buildUrlEvidenceCard(Map<String, Object> result,
                                                     Map<String, Object> pageEvidence,
                                                     String query,
                                                     SearchStrategy strategy) {
        String url = firstNonBlank(stringValue(result.get("url")), stringValue(pageEvidence.get("url")));
        String title = firstNonBlank(
            stringValue(result.get("title")),
            firstNonBlank(stringValue(pageEvidence.get("title")), url)
        );
        String snippet = compactText(firstNonBlank(
            stringValue(result.get("snippet")),
            firstNonBlank(stringValue(pageEvidence.get("description")), stringValue(pageEvidence.get("excerpt")))
        ), properties.getUrlEvidence().getMaxSnippetChars());
        String excerpt = firstNonBlank(stringValue(pageEvidence.get("excerpt")), "");
        String summary = compactText(firstNonBlank(
            stringValue(pageEvidence.get("summary")),
            buildEvidenceSummary(firstNonBlank(excerpt, snippet), query)
        ), properties.getUrlEvidence().getMaxSummaryChars());
        if (summary.isBlank()) {
            summary = compactText(firstNonBlank(snippet, title), properties.getUrlEvidence().getMaxSummaryChars());
        }
        String status = firstNonBlank(
            stringValue(pageEvidence.get("status")),
            firstNonBlank(stringValue(pageEvidence.get("statusCode")), isDocumentUrl(url) ? "document_or_binary" : "not_fetched")
        );
        String freshness = firstNonBlank(
            stringValue(result.get("publishedAt")),
            firstNonBlank(stringValue(pageEvidence.get("publishedAt")), stringValue(result.get("freshness")))
        );
        double relevanceScore = numberValue(firstObject(result.get("relevanceScore"), result.get("relevance_score")), 0);
        String sourceType = classifySourceType(url, title, stringValue(result.get("source")));
        double trustScore = domainTrustScore(url);
        double freshnessScore = freshnessScore(result, firstNonBlank(excerpt, summary), strategy);
        List<String> keyPoints = keyPoints(firstNonBlank(excerpt, summary), query);
        List<String> qualityFlags = qualityFlags(status, summary, pageEvidence, trustScore);
        List<String> keywords = evidenceKeywords(query, title, snippet, summary);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("url", url);
        evidence.put("title", title);
        evidence.put("snippet", snippet);
        evidence.put("summary", summary);
        evidence.put("domain", hostOf(url));
        evidence.put("language", detectLanguage(title + " " + snippet + " " + summary));
        evidence.put("content_type", classifyContentType(url, title, stringValue(result.get("source"))));
        evidence.put("source_type", sourceType);
        evidence.put("relevance_score", relevanceScore);
        evidence.put("rerank_score", relevanceScore);
        evidence.put("trust_score", trustScore);
        evidence.put("freshness_score", freshnessScore);
        evidence.put("strategy_fit_score", sourceTypeFitScore(sourceType, strategy));
        evidence.put("freshness", freshness);
        evidence.put("status", status);
        evidence.put("key_points", keyPoints);
        evidence.put("quality_flags", qualityFlags);
        evidence.put("keywords", keywords);
        evidence.put("source", firstNonBlank(stringValue(result.get("source")), "search_result"));
        copyIfPresent(pageEvidence, evidence, "statusCode");
        copyIfPresent(pageEvidence, evidence, "contentLength");
        copyIfPresent(pageEvidence, evidence, "contentTruncated");
        copyIfPresent(pageEvidence, evidence, "fetchError");
        return evidence;
    }

    private Map<String, Object> basePageEvidence(Map<String, Object> result,
                                                 String url,
                                                 String status,
                                                 int statusCode) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rank", result == null ? null : result.get("rank"));
        evidence.put("title", result == null ? url : firstNonBlank(stringValue(result.get("title")), url));
        evidence.put("url", url);
        evidence.put("excerpt", "");
        evidence.put("summary", "");
        evidence.put("contentLength", 0);
        evidence.put("contentTruncated", false);
        evidence.put("status", status);
        if (statusCode > 0) {
            evidence.put("statusCode", statusCode);
        }
        return evidence;
    }

    private String pageMetaDescription(Document page) {
        if (page == null) {
            return "";
        }
        return compactText(firstNonBlank(
            metaContent(page, "meta[name=description]"),
            firstNonBlank(metaContent(page, "meta[property=og:description]"), metaContent(page, "meta[name=twitter:description]"))
        ), properties.getUrlEvidence().getMaxSnippetChars());
    }

    private String publishedAt(Document page) {
        if (page == null) {
            return "";
        }
        return firstNonBlank(
            metaContent(page, "meta[property=article:published_time]"),
            firstNonBlank(
                metaContent(page, "meta[name=date]"),
                firstNonBlank(metaContent(page, "meta[name=publishdate]"), firstElementAttr(page, "time[datetime]", "datetime"))
            )
        );
    }

    private String metaContent(Document page, String selector) {
        Element element = page.selectFirst(selector);
        return element == null ? "" : element.attr("content").replaceAll("\\s+", " ").trim();
    }

    private String firstElementAttr(Document page, String selector, String attr) {
        Element element = page.selectFirst(selector);
        return element == null ? "" : element.attr(attr).replaceAll("\\s+", " ").trim();
    }

    private String buildEvidenceSummary(String text, String query) {
        String source = buildTextExcerpt(firstNonBlank(text, ""), query, properties.getUrlEvidence().getMaxSummaryChars() * 2);
        if (source.isBlank()) {
            return "";
        }
        String[] parts = source.split("(?<=[。！？.!?])\\s+|(?<=[。！？.!?])");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String sentence = part.replaceAll("\\s+", " ").trim();
            if (sentence.isBlank()) {
                continue;
            }
            if (builder.length() + sentence.length() > properties.getUrlEvidence().getMaxSummaryChars()) {
                break;
            }
            builder.append(sentence);
            if (builder.length() >= properties.getUrlEvidence().getMaxSummaryChars() / 2) {
                break;
            }
        }
        String summary = builder.length() == 0 ? source : builder.toString();
        return compactText(summary, properties.getUrlEvidence().getMaxSummaryChars());
    }

    private List<String> evidenceKeywords(String query, String title, String snippet, String summary) {
        int limit = Math.max(1, properties.getUrlEvidence().getMaxKeywords());
        Set<String> keywords = new LinkedHashSet<>();
        for (String term : queryTerms(query)) {
            if (keywords.size() >= limit) {
                break;
            }
            keywords.add(term);
        }
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}|[\\u4e00-\\u9fa5]{2,8}|\\d{4,}").matcher(
            String.join(" ", firstNonBlank(title, ""), firstNonBlank(snippet, ""), firstNonBlank(summary, ""))
        );
        while (matcher.find() && keywords.size() < limit) {
            String keyword = matcher.group().trim();
            if (!keyword.isBlank()) {
                keywords.add(keyword);
            }
        }
        return new ArrayList<>(keywords);
    }

    private String classifyContentType(String url, String title, String source) {
        String value = (firstNonBlank(url, "") + " " + firstNonBlank(title, "") + " " + firstNonBlank(source, "")).toLowerCase(Locale.ROOT);
        if (isDocumentUrl(url)) {
            return "document";
        }
        if (containsAny(value, List.of("forum", "bbs", "thread", "post", "zhihu.com", "reddit.com", "tieba"))) {
            return "forum";
        }
        if (containsAny(value, List.of("news", "article", "notice", "announcement", "公告", "新闻", "资讯"))) {
            return "news";
        }
        if (containsAny(value, List.of("product", "item", "sku", "goods", "shop", "mall", "商品", "产品"))) {
            return "product";
        }
        if (containsAny(value, List.of("search", "site_search"))) {
            return "search_result";
        }
        return "article";
    }

    private String classifySourceType(String url, String title, String source) {
        String host = firstNonBlank(hostOf(url), "").toLowerCase(Locale.ROOT);
        String value = (host + " " + firstNonBlank(url, "") + " " + firstNonBlank(title, "") + " " + firstNonBlank(source, "")).toLowerCase(Locale.ROOT);
        if (isDocumentUrl(url)) {
            return "document";
        }
        if (host.endsWith(".gov") || host.contains(".gov.") || host.endsWith(".edu") || host.contains(".edu.")) {
            return "official";
        }
        if (containsAny(value, List.of("official", "官网", "\u5b98\u65b9", "investor", "ir.", "docs.", "developer.", "api."))) {
            return "official";
        }
        if (containsAny(value, List.of("news", "reuters", "bloomberg", "apnews", "xinhua", "xinhuanet", "people.com.cn", "news.cn", "\u65b0\u95fb", "\u8d44\u8baf"))) {
            return "news";
        }
        if (containsAny(value, List.of("forum", "bbs", "thread", "post", "reddit.com", "zhihu.com", "tieba", "stackoverflow.com"))) {
            return "forum";
        }
        if (containsAny(value, List.of("shop", "mall", "product", "item", "sku", "amazon.", "jd.com", "taobao", "tmall", "\u5546\u54c1", "\u4ea7\u54c1"))) {
            return "commerce";
        }
        if (containsAny(value, List.of("paper", "journal", "arxiv", "doi.org", "scholar", "pubmed", "\u8bba\u6587", "\u5b66\u672f"))) {
            return "academic";
        }
        return "web";
    }

    private double domainTrustScore(String url) {
        String host = firstNonBlank(hostOf(url), "").toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            return 0.25;
        }
        if (host.endsWith(".gov") || host.contains(".gov.") || host.endsWith(".edu") || host.contains(".edu.")) {
            return 1.0;
        }
        if (containsAny(host, List.of("wikipedia.org", "reuters.com", "bloomberg.com", "apnews.com", "xinhua", "people.com.cn", "sec.gov"))) {
            return 0.92;
        }
        if (containsAny(host, List.of("github.com", "microsoft.com", "openai.com", "oracle.com", "spring.io", "apache.org", "docs."))) {
            return 0.88;
        }
        if (containsAny(host, List.of("sse.com.cn", "szse.cn", "cninfo.com.cn", "nasdaq.com", "nyse.com"))) {
            return 0.9;
        }
        if (containsAny(host, List.of("reddit.com", "zhihu.com", "tieba", "quora.com", "medium.com", "blogspot", "wordpress"))) {
            return 0.55;
        }
        if (containsAny(host, List.of("download", "file", "cdn"))) {
            return 0.5;
        }
        return 0.7;
    }

    private double freshnessScore(Map<String, Object> result, String text, SearchStrategy strategy) {
        String combined = String.join(" ",
            firstNonBlank(stringValue(result.get("publishedAt")), ""),
            firstNonBlank(stringValue(result.get("freshness")), ""),
            firstNonBlank(stringValue(result.get("title")), ""),
            firstNonBlank(stringValue(result.get("url")), ""),
            firstNonBlank(text, "")
        );
        int year = latestYearMention(combined);
        int currentYear = LocalDate.now().getYear();
        if (year >= currentYear) {
            return 1.0;
        }
        if (year == currentYear - 1) {
            return strategy != null && strategy.freshnessSensitive() ? 0.78 : 0.86;
        }
        if (year > 0) {
            int age = Math.max(1, currentYear - year);
            return Math.max(0.18, 0.75 - age * 0.11);
        }
        return strategy != null && strategy.freshnessSensitive() ? 0.42 : 0.65;
    }

    private int latestYearMention(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("\\b(20[0-9]{2})\\b").matcher(text);
        int latest = 0;
        while (matcher.find()) {
            try {
                latest = Math.max(latest, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed year-like values.
            }
        }
        return latest;
    }

    private double sourceTypeFitScore(String sourceType, SearchStrategy strategy) {
        if (strategy == null || sourceType == null || sourceType.isBlank()) {
            return 0.5;
        }
        List<String> modes = strategy.modes();
        if (modes.contains("finance")) {
            return switch (sourceType) {
                case "official", "document", "news" -> 1.0;
                case "web" -> 0.65;
                default -> 0.35;
            };
        }
        if (modes.contains("news")) {
            return switch (sourceType) {
                case "news", "official" -> 1.0;
                case "forum" -> 0.55;
                default -> 0.65;
            };
        }
        if (modes.contains("technical") || modes.contains("tutorial")) {
            return switch (sourceType) {
                case "official", "document", "web" -> 1.0;
                case "forum" -> 0.75;
                default -> 0.6;
            };
        }
        if (modes.contains("academic")) {
            return switch (sourceType) {
                case "academic", "document", "official" -> 1.0;
                default -> 0.45;
            };
        }
        if (modes.contains("product")) {
            return switch (sourceType) {
                case "commerce", "official", "web" -> 1.0;
                case "forum" -> 0.7;
                default -> 0.55;
            };
        }
        return "official".equals(sourceType) ? 0.9 : 0.65;
    }

    private List<String> keyPoints(String text, String query) {
        String source = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (source.isBlank()) {
            return List.of();
        }
        List<String> terms = queryTerms(query);
        String[] sentences = source.split("(?<=[。！？.!?])\\s+|(?<=[。！？.!?])");
        List<Map<String, Object>> ranked = new ArrayList<>();
        int index = 0;
        for (String sentenceValue : sentences) {
            String sentence = sentenceValue.replaceAll("\\s+", " ").trim();
            if (sentence.length() < 20) {
                continue;
            }
            double score = Math.max(0, 100 - index++);
            String lower = sentence.toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                    score += 20;
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", score);
            item.put("text", compactText(sentence, 220));
            ranked.add(item);
        }
        ranked.sort((left, right) -> Double.compare(numberValue(right.get("score"), 0), numberValue(left.get("score"), 0)));
        return ranked.stream()
            .map(item -> stringValue(item.get("text")))
            .filter(value -> value != null && !value.isBlank())
            .limit(3)
            .toList();
    }

    private List<String> qualityFlags(String status,
                                      String summary,
                                      Map<String, Object> pageEvidence,
                                      double trustScore) {
        List<String> flags = new ArrayList<>();
        String normalizedStatus = firstNonBlank(status, "").toLowerCase(Locale.ROOT);
        if (normalizedStatus.contains("404") || normalizedStatus.contains("timeout") || normalizedStatus.contains("error") || normalizedStatus.contains("blocked")) {
            flags.add("fetch_issue");
        }
        if (summary == null || summary.length() < 60) {
            flags.add("thin_summary");
        }
        if (Boolean.TRUE.equals(pageEvidence.get("contentTruncated"))) {
            flags.add("content_truncated");
        }
        if (pageEvidence.get("fetchError") != null) {
            flags.add("fetch_error");
        }
        if (trustScore < 0.6) {
            flags.add("low_domain_trust");
        }
        return flags;
    }

    private String detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                cjk++;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                latin++;
            }
        }
        if (cjk == 0 && latin == 0) {
            return "unknown";
        }
        return cjk >= latin ? "zh" : "en";
    }

    private double numberValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Performs the fetch page excerpts operation.
     *
     * @param results the results value
     * @param query the query value
     * @return the operation result
     */
    private List<Map<String, Object>> fetchPageExcerpts(List<Map<String, Object>> results,
                                                        String query,
                                                        WebSearchRequestContext context,
                                                        List<Map<String, Object>> networkAudit) {
        int limit = results == null ? 0 : Math.min(properties.getMaxPagesToFetch(), results.size());
        return fetchPageExcerpts(results, query, context, networkAudit, limit);
    }

    private List<Map<String, Object>> fetchPageExcerpts(List<Map<String, Object>> results,
                                                        String query,
                                                        WebSearchRequestContext context,
                                                        List<Map<String, Object>> networkAudit,
                                                        int requestedLimit) {
        if (!properties.isFetchPages() || results == null || results.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(0, Math.min(requestedLimit, results.size()));
        if (limit <= 0) {
            return List.of();
        }

        List<Map<String, Object>> excerpts = new ArrayList<>();
        int attempts = 0;
        for (Map<String, Object> result : results) {
            if (attempts >= limit || excerpts.size() >= limit) {
                break;
            }
            String url = stringValue(result.get("url"));
            if (!isFetchablePageUrl(url)) {
                Map<String, Object> evidence = basePageEvidence(result, url, "document_or_binary", 0);
                evidence.put("fetchError", "unsupported content type for inline HTML extraction");
                excerpts.add(evidence);
                attempts++;
                continue;
            }
            if (!isAllowedUrl(url, context)) {
                addAudit(networkAudit, query, url, "page", null, 0, 0, "BLOCKED", "domain not allowed");
                Map<String, Object> evidence = basePageEvidence(result, url, "blocked", 0);
                evidence.put("fetchError", "domain not allowed");
                excerpts.add(evidence);
                continue;
            }
            attempts++;
            try {
                long startedAt = System.currentTimeMillis();
                log.info("Web search page fetch started attempt={}/{} rank={} url={}",
                    attempts,
                    limit,
                    result.get("rank"),
                    url);
                HtmlResponse response = sendHtmlRequest(
                    url,
                    Map.of(),
                    query,
                    "page",
                    context,
                    networkAudit
                );
                int statusCode = response.statusCode();
                Document page = response.document();

                String pageText = extractReadableText(page);
                if (pageText.isBlank()) {
                    Map<String, Object> evidence = basePageEvidence(result, url, String.valueOf(statusCode), statusCode);
                    evidence.put("fetchError", "empty readable content");
                    evidence.put("description", pageMetaDescription(page));
                    excerpts.add(evidence);
                    continue;
                }
                String excerpt = buildTextExcerpt(pageText, query, properties.getPageExcerptChars());
                String metaDescription = pageMetaDescription(page);

                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("rank", result.get("rank"));
                evidence.put("title", firstNonBlank(stringValue(result.get("title")), page.title()));
                evidence.put("url", url);
                evidence.put("description", metaDescription);
                evidence.put("excerpt", excerpt);
                evidence.put("summary", buildEvidenceSummary(pageText, query));
                evidence.put("publishedAt", firstNonBlank(stringValue(result.get("publishedAt")), publishedAt(page)));
                evidence.put("contentLength", pageText.length());
                evidence.put("contentTruncated", pageText.length() > excerpt.length());
                evidence.put("statusCode", statusCode);
                evidence.put("status", String.valueOf(statusCode));
                excerpts.add(evidence);
                log.info("Web search page fetch succeeded attempt={}/{} rank={} statusCode={} durationMs={} excerptChars={}",
                    attempts,
                    limit,
                    result.get("rank"),
                    statusCode,
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    excerpt.length());
            } catch (Exception ex) {
                log.warn("Web search page fetch failed attempt={}/{} rank={} url={} error={}",
                    attempts,
                    limit,
                    result.get("rank"),
                    url,
                    ex.getMessage());
                Map<String, Object> evidence = basePageEvidence(result, url, "timeout_or_error", 0);
                evidence.put("fetchError", ex.getMessage());
                excerpts.add(evidence);
            }
        }
        return excerpts;
    }

    private List<Map<String, Object>> discoverSiteSearchResults(List<Map<String, Object>> results,
                                                                String query,
                                                                int resultLimit,
                                                                WebSearchRequestContext context,
                                                                List<Map<String, Object>> networkAudit,
                                                                boolean inspectTopFive,
                                                                String siteSearchMode) {
        WebSearchToolProperties.SiteSearchProperties siteSearch = properties.getSiteSearch();
        if (siteSearch == null || !siteSearch.isEnabled() || results == null || results.isEmpty()
            || query == null || query.isBlank()) {
            return List.of();
        }
        int configuredInspectLimit = Math.max(0, siteSearch.getMaxPagesToInspect());
        int inspectLimit = inspectTopFive
            ? Math.min(5, results.size())
            : Math.max(0, Math.min(3, configuredInspectLimit));
        int secondaryLimit = inspectTopFive
            ? Math.max(5, siteSearch.getMaxSecondaryPages())
            : Math.max(0, siteSearch.getMaxSecondaryPages());
        if (inspectLimit <= 0 || secondaryLimit <= 0) {
            return List.of();
        }

        List<Map<String, Object>> discovered = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (Map<String, Object> result : results) {
            String url = stringValue(result.get("url"));
            if (url != null && !url.isBlank()) {
                seenUrls.add(normalizeComparableUrl(url));
            }
        }

        int inspected = 0;
        int secondaryRequests = 0;
        log.info("Web search site-search discovery started query={} inspectLimit={} secondaryLimit={} resultLimit={}",
            query,
            inspectLimit,
            secondaryLimit,
            resultLimit);
        for (Map<String, Object> result : results) {
            if (inspected >= inspectLimit || secondaryRequests >= secondaryLimit || discovered.size() >= resultLimit) {
                break;
            }
            String pageUrl = stringValue(result.get("url"));
            if (!isFetchablePageUrl(pageUrl) || !isAllowedUrl(pageUrl, context)) {
                continue;
            }
            inspected++;
            try {
                log.info("Web search site-search inspect started inspected={}/{} url={}",
                    inspected,
                    inspectLimit,
                    pageUrl);
                if (secondaryRequests < secondaryLimit && looksLikeShanghaiStockExchangeUrl(pageUrl)) {
                    secondaryRequests++;
                    List<Map<String, Object>> knownResults = runKnownSiteSearch(
                        null,
                        pageUrl,
                        query,
                        seenUrls,
                        Math.max(1, siteSearch.getMaxLinksPerPage()),
                        context,
                        networkAudit,
                        SITE_SEARCH_MODE_JAVA
                    );
                    if (!knownResults.isEmpty()) {
                        result.put("siteSearchAvailable", true);
                        result.put("siteSearchType", "known_jsonp");
                        result.put("siteSearchResultCount", knownResults.size());
                        discovered.addAll(knownResults);
                        log.info("Web search site-search known endpoint succeeded url={} resultCount={} totalDiscovered={} results={}",
                            pageUrl,
                            knownResults.size(),
                            discovered.size(),
                            loggableSearchResults(knownResults));
                        continue;
                    }
                }
                HtmlResponse pageResponse = sendSearchPageRequest(
                    pageUrl,
                    Map.of(),
                    query,
                    "site_search_page",
                    context,
                    networkAudit,
                    "GET",
                    siteSearchMode
                );
                result.put("siteSearchPageInspected", true);
                log.info("Web search site-search page inspected url={}", pageUrl);
                if (secondaryRequests < secondaryLimit && knownSearchEndpoint(pageResponse.document(), pageUrl) != null) {
                    secondaryRequests++;
                    List<Map<String, Object>> knownResults = runKnownSiteSearch(
                        pageResponse.document(),
                        pageUrl,
                        query,
                        seenUrls,
                        Math.max(1, siteSearch.getMaxLinksPerPage()),
                        context,
                        networkAudit,
                        SITE_SEARCH_MODE_JAVA
                    );
                    if (!knownResults.isEmpty()) {
                        result.put("siteSearchAvailable", true);
                        result.put("siteSearchType", "known_jsonp");
                        result.put("siteSearchResultCount", knownResults.size());
                        discovered.addAll(knownResults);
                        log.info("Web search site-search known endpoint succeeded url={} resultCount={} totalDiscovered={} results={}",
                            pageUrl,
                            knownResults.size(),
                            discovered.size(),
                            loggableSearchResults(knownResults));
                        continue;
                    }
                }
                List<SiteSearchForm> forms = findSiteSearchForms(pageResponse.document(), pageUrl, query);
                if (forms.isEmpty()) {
                    forms = discoverSearchFormsFromEntrypoints(
                        pageResponse.document(),
                        pageUrl,
                        query,
                        context,
                        networkAudit,
                        inspectTopFive ? 5 : Math.max(0, inspectLimit - inspected),
                        siteSearchMode
                    );
                    inspected += Math.max(0, Math.min(inspectLimit - inspected, inspectedEntrypointCount(forms)));
                }
                if (forms.isEmpty()) {
                    result.put("siteSearchAvailable", false);
                    log.info("Web search site-search no searchable form found url={}", pageUrl);
                    continue;
                }
                result.put("siteSearchAvailable", true);
                result.put("siteSearchFormCount", forms.size());
                log.info("Web search site-search forms found url={} formCount={}", pageUrl, forms.size());
                for (SiteSearchForm form : forms) {
                    if (secondaryRequests >= secondaryLimit || discovered.size() >= resultLimit) {
                        break;
                    }
                    secondaryRequests++;
                    log.info("Web search site-search submit started sourceUrl={} method={} actionUrl={} submittedUrl={}",
                        pageUrl,
                        form.method(),
                        form.actionUrl(),
                        form.submittedUrl());
                    HtmlResponse secondaryResponse = sendSearchPageRequest(
                        form.actionUrl(),
                        form.parameters(),
                        query,
                        "site_search",
                        context,
                        networkAudit,
                        form.method(),
                        siteSearchMode
                    );
                    List<Map<String, Object>> secondaryResults = parseSiteSearchLinks(
                        secondaryResponse.document(),
                        pageUrl,
                        form.submittedUrl(),
                        query,
                        seenUrls,
                        Math.max(1, siteSearch.getMaxLinksPerPage())
                    );
                    discovered.addAll(secondaryResults);
                    log.info("Web search site-search submit completed sourceUrl={} resultCount={} totalDiscovered={} results={}",
                        pageUrl,
                        secondaryResults.size(),
                        discovered.size(),
                        loggableSearchResults(secondaryResults));
                }
            } catch (Exception ex) {
                result.put("siteSearchAvailable", false);
                result.put("siteSearchError", ex.getMessage());
                log.warn("Web search site-search enrichment failed url={} error={}", pageUrl, ex.getMessage());
            }
        }
        log.info("Web search site-search discovery completed query={} inspected={} secondaryRequests={} discovered={}",
            query,
            inspected,
            secondaryRequests,
            discovered.size());
        return discovered;
    }

    private List<Map<String, Object>> runTargetedKnownSiteSearch(WebSearchQueryIntent queryIntent,
                                                                 String query,
                                                                 List<Map<String, Object>> primaryResults,
                                                                 int resultLimit,
                                                                 WebSearchRequestContext context,
                                                                 List<Map<String, Object>> networkAudit,
                                                                 String siteSearchMode) {
        if (queryIntent == null || query == null || query.isBlank()
            || queryIntent.targetUrl() == null || queryIntent.targetUrl().isBlank()) {
            return List.of();
        }
        if (knownSearchEndpoint(null, queryIntent.targetUrl()) == null) {
            return List.of();
        }
        Set<String> seenUrls = new LinkedHashSet<>();
        if (primaryResults != null) {
            for (Map<String, Object> result : primaryResults) {
                String url = stringValue(result.get("url"));
                if (url != null && !url.isBlank()) {
                    seenUrls.add(normalizeComparableUrl(url));
                }
            }
        }
        try {
            log.info("Web search targeted known site-search started targetHost={} query={} resultLimit={}",
                queryIntent.targetHost(),
                query,
                resultLimit);
            List<Map<String, Object>> results = runKnownSiteSearch(
                null,
                queryIntent.targetUrl(),
                query,
                seenUrls,
                Math.max(1, resultLimit),
                context,
                networkAudit,
                siteSearchMode
            );
            log.info("Web search targeted known site-search completed targetHost={} resultCount={}",
                queryIntent.targetHost(),
                results.size());
            return results;
        } catch (Exception ex) {
            log.warn("Web search targeted known site-search failed targetHost={} error={}",
                queryIntent.targetHost(),
                ex.getMessage());
            return List.of();
        }
    }

    private List<SiteSearchForm> discoverSearchFormsFromEntrypoints(Document document,
                                                                    String pageUrl,
                                                                    String query,
                                                                    WebSearchRequestContext context,
                                                                    List<Map<String, Object>> networkAudit,
                                                                    int maxAdditionalInspections,
                                                                    String siteSearchMode) {
        if (maxAdditionalInspections <= 0) {
            return List.of();
        }
        List<String> candidates = siteSearchEntrypoints(document, pageUrl);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<SiteSearchForm> forms = new ArrayList<>();
        Set<String> seenForms = new LinkedHashSet<>();
        int inspected = 0;
        for (String candidateUrl : candidates) {
            if (inspected >= maxAdditionalInspections || !isFetchablePageUrl(candidateUrl) || !isAllowedUrl(candidateUrl, context)) {
                continue;
            }
            inspected++;
            try {
                log.info("Web search site-search entrypoint inspect started sourceUrl={} entrypoint={}",
                    pageUrl,
                    candidateUrl);
                HtmlResponse response = sendSearchPageRequest(
                    candidateUrl,
                    Map.of(),
                    query,
                    "site_search_entrypoint",
                    contextWithReferer(context, pageUrl, query),
                    networkAudit,
                    "GET",
                    siteSearchMode
                );
                for (SiteSearchForm form : findSiteSearchForms(response.document(), candidateUrl, query)) {
                    String key = normalizeComparableUrl(form.submittedUrl());
                    if (seenForms.add(key)) {
                        forms.add(form);
                    }
                }
                log.info("Web search site-search entrypoint inspect completed entrypoint={} formCount={} totalForms={}",
                    candidateUrl,
                    forms.size(),
                    seenForms.size());
                if (!forms.isEmpty()) {
                    break;
                }
            } catch (Exception ex) {
                log.warn("Web search site-search entrypoint inspect failed sourceUrl={} entrypoint={} error={}",
                    pageUrl,
                    candidateUrl,
                    ex.getMessage());
            }
        }
        return forms;
    }

    private int inspectedEntrypointCount(List<SiteSearchForm> forms) {
        if (forms == null || forms.isEmpty()) {
            return 0;
        }
        return (int) forms.stream()
            .map(SiteSearchForm::sourcePageUrl)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .count();
    }

    private List<String> siteSearchEntrypoints(Document document, String pageUrl) {
        String origin = originOf(pageUrl);
        if (origin == null || origin.isBlank()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        if (document != null) {
            for (Element link : document.select("a[href]")) {
                String href = normalizeSearchUrl(link.absUrl("href"));
                if (!isLikelySearchEntrypoint(href, link.text())) {
                    continue;
                }
                String host = hostOf(href);
                String sourceHost = hostOf(pageUrl);
                if (host != null && sourceHost != null && host.equalsIgnoreCase(sourceHost)) {
                    candidates.add(href);
                }
            }
        }
        candidates.add(origin + "/");
        candidates.add(origin + "/search");
        candidates.add(origin + "/search/");
        candidates.add(origin + "/home/search");
        candidates.add(origin + "/home/search/");
        candidates.add(origin + "/search.html");
        candidates.add(origin + "/search/index.html");
        candidates.add(origin + "/sousuo");
        candidates.add(origin + "/sousuo/");
        String current = normalizeComparableUrl(pageUrl);
        return candidates.stream()
            .filter(url -> url != null && !url.isBlank())
            .filter(url -> !normalizeComparableUrl(url).equals(current))
            .toList();
    }

    private boolean isLikelySearchEntrypoint(String href, String label) {
        if (!isHttpUrl(href)) {
            return false;
        }
        String text = (href + " " + firstNonBlank(label, "")).toLowerCase(Locale.ROOT);
        return containsAny(text, List.of(
            "search",
            "query",
            "find",
            "keyword",
            "sousuo",
            "搜索",
            "检索",
            "查询",
            "站内"
        ));
    }

    private List<Map<String, Object>> runKnownSiteSearch(Document document,
                                                         String sourcePageUrl,
                                                         String query,
                                                         Set<String> seenUrls,
                                                         int maxLinks,
                                                         WebSearchRequestContext context,
                                                         List<Map<String, Object>> networkAudit,
                                                         String siteSearchMode) throws Exception {
        String endpoint = knownSearchEndpoint(document, sourcePageUrl);
        if (endpoint == null || endpoint.isBlank()) {
            return List.of();
        }
        String callback = "jsonpCallback" + Math.abs((query + ":" + sourcePageUrl).hashCode());
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("jsonCallBack", callback);
        parameters.put("keyword", query);
        parameters.put("page", "0");
        parameters.put("limit", String.valueOf(Math.max(1, maxLinks)));
        parameters.put("spaceId", "3");
        parameters.put("keywordPosition", "content");
        parameters.put("orderByKey", "score");
        parameters.put("orderByDirection", "DESC");
        parameters.put("searchMode", "fuzzy");
        parameters.put("channelId", "10001");

        WebSearchRequestContext siteContext = contextWithReferer(context, sourcePageUrl, query);
        String submittedUrl = urlWithQueryParams(endpoint, parameters);
        log.info("Web search site-search known endpoint request started sourceUrl={} endpoint={} query={}",
            sourcePageUrl,
            endpoint,
            query);
        HtmlResponse response = sendSearchPageRequest(
            endpoint,
            parameters,
            query,
            "site_search_known",
            siteContext,
            networkAudit,
            "GET",
            siteSearchMode
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Web search site-search known endpoint returned non-success status sourceUrl={} statusCode={}",
                sourcePageUrl,
                response.statusCode());
            return List.of();
        }
        String payload = response.document() == null ? "" : response.document().text();
        List<Map<String, Object>> results;
        try {
            results = parseKnownJsonpSearchResults(
                payload,
                sourcePageUrl,
                submittedUrl,
                query,
                seenUrls,
                maxLinks
            );
        } catch (Exception ex) {
            log.warn("Web search site-search known endpoint parse failed sourceUrl={} statusCode={} error={}",
                sourcePageUrl,
                response.statusCode(),
                ex.getMessage());
            return List.of();
        }
        log.info("Web search site-search known endpoint response sourceUrl={} statusCode={} resultCount={}",
            sourcePageUrl,
            response.statusCode(),
            results.size());
        return results;
    }

    private String knownSearchEndpoint(Document document, String sourcePageUrl) {
        if (looksLikeShanghaiStockExchangeUrl(sourcePageUrl)) {
            return "https://query.sse.com.cn/search/getESSearchDoc.do";
        }
        if (document == null) {
            return null;
        }
        String html = document.outerHtml();
        if (!html.contains("sseQueryURL") || !html.contains("getESSearchDoc.do")) {
            return null;
        }
        Matcher matcher = Pattern.compile("sseQueryURL\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html);
        if (!matcher.find()) {
            return null;
        }
        String base = normalizeProtocolRelativeUrl(matcher.group(1), sourcePageUrl);
        if (base == null || base.isBlank()) {
            return null;
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + "search/getESSearchDoc.do";
    }

    private boolean looksLikeShanghaiStockExchangeUrl(String url) {
        String host = hostOf(url);
        return host != null && (host.equalsIgnoreCase("sse.com.cn") || host.toLowerCase(Locale.ROOT).endsWith(".sse.com.cn"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseKnownJsonpSearchResults(String payload,
                                                                   String sourcePageUrl,
                                                                   String searchUrl,
                                                                   String query,
                                                                   Set<String> seenUrls,
                                                                   int maxLinks) throws IOException {
        String json = unwrapJsonp(payload);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Map<String, Object> root = objectMapper.readValue(json, Map.class);
        Object dataValue = root.get("data");
        if (!(dataValue instanceof Map<?, ?> data)) {
            return List.of();
        }
        Object listValue = data.get("knowledgeList");
        if (!(listValue instanceof List<?> knowledgeList)) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int rank = 1;
        for (Object value : knowledgeList) {
            if (results.size() >= maxLinks || !(value instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            String url = absoluteSiteUrl(firstNonBlank(
                stringValue(item.get("url")),
                extendValue(item.get("extend"), "CURL")
            ), sourcePageUrl);
            if (!isHttpUrl(url)) {
                continue;
            }
            String comparable = normalizeComparableUrl(url);
            if (!seenUrls.add(comparable)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rank", rank++);
            result.put("title", cleanHtmlText(firstNonBlank(stringValue(item.get("title")), url)));
            result.put("url", url);
            result.put("snippet", buildTextExcerpt(cleanHtmlText(stringValue(item.get("rtfContent"))), query,
                Math.min(500, properties.getPageExcerptChars())));
            result.put("source", "site_search_known");
            result.put("sourcePageUrl", sourcePageUrl);
            result.put("siteSearchUrl", searchUrl);
            result.put("siteSearchEngine", "sse_ess_jsonp");
            result.put("publishedAt", item.get("createTime"));
            result.put("documentId", item.get("documentId"));
            result.put("score", item.get("score"));
            result.put("totalSize", data.get("totalSize"));
            result.put("securityCode", extendValue(item.get("extend"), "ZQDM"));
            result.put("securityName", extendValue(item.get("extend"), "GSJC"));
            results.add(result);
        }
        return results;
    }

    private String unwrapJsonp(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String value = payload.trim();
        int start = value.indexOf('(');
        int end = value.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return value.substring(start + 1, end);
        }
        return value.startsWith("{") ? value : null;
    }

    private String extendValue(Object extendValue, String name) {
        if (!(extendValue instanceof List<?> entries)) {
            return null;
        }
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map && name.equals(stringValue(map.get("name")))) {
                return stringValue(map.get("value"));
            }
        }
        return null;
    }

    private String cleanHtmlText(String value) {
        return value == null ? "" : Jsoup.parse(value).text().replaceAll("\\s+", " ").trim();
    }

    private String absoluteSiteUrl(String url, String sourcePageUrl) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String value = url.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("www.")) {
            return "https://" + value;
        }
        try {
            URI base = URI.create(sourcePageUrl);
            if (looksLikeShanghaiStockExchangeUrl(sourcePageUrl) && value.startsWith("/")) {
                return "https://www.sse.com.cn" + value;
            }
            return base.resolve(value).toString();
        } catch (Exception ex) {
            return value;
        }
    }

    private String normalizeProtocolRelativeUrl(String value, String sourcePageUrl) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("//")) {
            String scheme = "https";
            try {
                scheme = firstNonBlank(URI.create(sourcePageUrl).getScheme(), "https");
            } catch (Exception ignored) {
                // Use https by default for protocol-relative search APIs.
            }
            return scheme + ":" + trimmed;
        }
        if (trimmed.startsWith("/")) {
            try {
                return URI.create(sourcePageUrl).resolve(trimmed).toString();
            } catch (Exception ignored) {
                return trimmed;
            }
        }
        return trimmed;
    }

    private WebSearchRequestContext contextWithReferer(WebSearchRequestContext context, String sourcePageUrl, String query) {
        String referer = looksLikeShanghaiStockExchangeUrl(sourcePageUrl)
            ? "https://www.sse.com.cn/home/search/?webswd=" + rawQueryValue(query)
            : sourcePageUrl;
        return new WebSearchRequestContext(
            context.query(),
            context.tenantId(),
            context.userId(),
            context.roles(),
            context.taskId(),
            context.agentId(),
            referer,
            context.allowedDomains(),
            context.blockedDomains()
        );
    }

    private List<SiteSearchForm> findSiteSearchForms(Document document, String pageUrl, String query) {
        return findSearchForms(document, pageUrl, query, true);
    }

    private List<SiteSearchForm> findSearchForms(Document document, String pageUrl, String query, boolean requireSiteSearchHints) {
        if (document == null) {
            return List.of();
        }
        List<SiteSearchForm> forms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element form : document.select("form")) {
            Element queryInput = bestSearchInput(form);
            if (queryInput == null) {
                continue;
            }
            String inputName = firstNonBlank(queryInput.attr("name"), queryInput.attr("id"));
            if (inputName == null || inputName.isBlank()) {
                continue;
            }
            String actionUrl = form.absUrl("action");
            if (actionUrl == null || actionUrl.isBlank()) {
                actionUrl = pageUrl;
            }
            if (requireSiteSearchHints && !looksLikeSiteSearchForm(form, actionUrl, queryInput)) {
                continue;
            }
            Map<String, String> parameters = formParameters(form, inputName, query);
            if (!parameters.containsKey(inputName)) {
                parameters.put(inputName, query);
            }
            String method = firstNonBlank(form.attr("method"), "GET").trim().toUpperCase(Locale.ROOT);
            if (!"POST".equals(method)) {
                method = "GET";
            }
            String submittedUrl = "POST".equals(method)
                ? actionUrl
                : urlWithRawQueryParams(actionUrl, parameters);
            String key = normalizeComparableUrl(submittedUrl);
            if (seen.add(key)) {
                forms.add(new SiteSearchForm(actionUrl, parameters, method, submittedUrl, pageUrl));
            }
        }
        return forms;
    }

    private Element bestSearchInput(Element form) {
        Element best = null;
        int bestScore = 0;
        for (Element input : form.select("input")) {
            String type = firstNonBlank(input.attr("type"), "text").toLowerCase(Locale.ROOT);
            if (Set.of("hidden", "submit", "button", "reset", "checkbox", "radio", "file", "image").contains(type)) {
                continue;
            }
            String name = firstNonBlank(input.attr("name"), input.attr("id"));
            if (name == null || name.isBlank()) {
                continue;
            }
            int score = searchInputScore(input);
            if (score > bestScore) {
                best = input;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private int searchInputScore(Element input) {
        int score = 0;
        String type = firstNonBlank(input.attr("type"), "text").toLowerCase(Locale.ROOT);
        if ("search".equals(type)) {
            score += 6;
        }
        String text = String.join(" ",
            input.attr("name"),
            input.attr("id"),
            input.attr("class"),
            input.attr("placeholder"),
            input.attr("aria-label")
        ).toLowerCase(Locale.ROOT);
        if (containsAny(text, properties.getSiteSearch().getInputNameHints())) {
            score += 5;
        }
        if (text.contains("搜索") || text.contains("检索") || text.contains("查询")
            || text.contains("股票") || text.contains("证券") || text.contains("代码")) {
            score += 5;
        }
        return score;
    }

    private boolean looksLikeSiteSearchForm(Element form, String actionUrl, Element input) {
        String text = String.join(" ",
            form.attr("id"),
            form.attr("class"),
            form.attr("role"),
            form.text(),
            actionUrl,
            input.attr("name"),
            input.attr("id"),
            input.attr("placeholder"),
            input.attr("aria-label")
        ).toLowerCase(Locale.ROOT);
        return containsAny(text, properties.getSiteSearch().getFormActionHints())
            || text.contains("搜索")
            || text.contains("检索")
            || text.contains("查询")
            || text.contains("股票")
            || text.contains("证券")
            || text.contains("代码");
    }

    private Map<String, String> formParameters(Element form, String queryInputName, String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Element field : form.select("input[name], select[name], textarea[name]")) {
            String name = field.attr("name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String tag = field.tagName();
            String type = field.attr("type").toLowerCase(Locale.ROOT);
            if ("input".equals(tag) && Set.of("submit", "button", "reset", "file", "image").contains(type)) {
                continue;
            }
            if ("input".equals(tag) && ("checkbox".equals(type) || "radio".equals(type)) && !field.hasAttr("checked")) {
                continue;
            }
            String value = name.equals(queryInputName)
                ? query
                : firstNonBlank(field.val(), field.attr("value"));
            parameters.put(name, firstNonBlank(value, ""));
        }
        parameters.put(queryInputName, query);
        return parameters;
    }

    private List<Map<String, Object>> parseSiteSearchLinks(Document document,
                                                           String sourcePageUrl,
                                                           String searchUrl,
                                                           String query,
                                                           Set<String> seenUrls,
                                                           int maxLinks) {
        if (document == null || maxLinks <= 0) {
            return List.of();
        }
        List<Map<String, Object>> links = new ArrayList<>();
        Element scope = first(
            document.selectFirst("main"),
            document.selectFirst("[role=main]"),
            document.selectFirst("[class*=search]"),
            document.selectFirst("[id*=search]"),
            document.body()
        );
        if (scope == null) {
            return List.of();
        }
        int rank = 1;
        for (Element link : scope.select("a[href]")) {
            if (links.size() >= maxLinks) {
                break;
            }
            String href = normalizeSearchUrl(link.absUrl("href"));
            if (!isUsefulSiteSearchLink(href, link.text(), sourcePageUrl, searchUrl, query)) {
                continue;
            }
            String comparable = normalizeComparableUrl(href);
            if (!seenUrls.add(comparable)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", rank++);
            item.put("title", firstNonBlank(link.text(), href));
            item.put("url", href);
            item.put("snippet", nearbyText(link, query));
            item.put("source", "site_search");
            item.put("sourcePageUrl", sourcePageUrl);
            item.put("siteSearchUrl", searchUrl);
            links.add(item);
        }
        return links;
    }

    private boolean isUsefulSiteSearchLink(String href,
                                           String text,
                                           String sourcePageUrl,
                                           String searchUrl,
                                           String query) {
        if (!isSupportedResultUrl(href)) {
            return false;
        }
        String normalized = normalizeComparableUrl(href);
        if (normalized.equals(normalizeComparableUrl(sourcePageUrl))
            || normalized.equals(normalizeComparableUrl(searchUrl))) {
            return false;
        }
        String host = hostOf(href);
        String sourceHost = hostOf(sourcePageUrl);
        if (host == null || sourceHost == null || !host.equalsIgnoreCase(sourceHost)) {
            return false;
        }
        String label = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (label.isBlank() && isDocumentUrl(href)) {
            label = fileNameFromUrl(href);
        }
        if (label.length() < 2) {
            return false;
        }
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        if (Set.of("home", "login", "more", "next", "previous", "首页", "登录", "更多", "下一页", "上一页")
            .contains(lowerLabel)) {
            return false;
        }
        String combined = (href + " " + label).toLowerCase(Locale.ROOT);
        for (String term : queryTerms(query)) {
            if (combined.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return true;
    }

    private String nearbyText(Element link, String query) {
        Element container = first(
            link.closest("li"),
            link.closest("article"),
            link.closest(".result"),
            link.parent()
        );
        String text = container == null ? link.text() : container.text();
        return buildTextExcerpt(text, query, Math.min(500, properties.getPageExcerptChars()));
    }

    private List<Map<String, Object>> mergeSearchResults(List<Map<String, Object>> primary,
                                                         List<Map<String, Object>> secondary,
                                                         int limit) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        appendSearchResults(merged, seen, primary);
        appendSearchResults(merged, seen, secondary);
        int rank = 1;
        for (Map<String, Object> item : merged) {
            item.put("rank", rank++);
        }
        return merged.size() <= limit ? merged : new ArrayList<>(merged.subList(0, limit));
    }

    private void appendSearchResults(List<Map<String, Object>> merged,
                                     Set<String> seen,
                                     List<Map<String, Object>> source) {
        if (source == null) {
            return;
        }
        for (Map<String, Object> item : source) {
            String url = stringValue(item.get("url"));
            if (url == null || url.isBlank() || !seen.add(normalizeComparableUrl(url))) {
                continue;
            }
            merged.add(new LinkedHashMap<>(item));
        }
    }

    private HtmlResponse sendHtmlRequest(String url,
                                         Map<String, String> queryParams,
                                         String query,
                                         String phase,
                                         WebSearchRequestContext context,
                                         List<Map<String, Object>> networkAudit) throws Exception {
        return sendHtmlRequest(url, queryParams, query, phase, context, networkAudit, "GET", true);
    }

    private HtmlResponse sendSearchPageRequest(String url,
                                               Map<String, String> queryParams,
                                               String query,
                                               String phase,
                                               WebSearchRequestContext context,
                                               List<Map<String, Object>> networkAudit) throws Exception {
        return sendSearchPageRequest(url, queryParams, query, phase, context, networkAudit, "GET");
    }

    private HtmlResponse sendSearchPageRequest(String url,
                                               Map<String, String> queryParams,
                                               String query,
                                               String phase,
                                               WebSearchRequestContext context,
                                               List<Map<String, Object>> networkAudit,
                                               String httpMethod) throws Exception {
        if (browserRenderingEnabled() && !shouldUseRawQueryParams(phase, httpMethod)) {
            HtmlResponse browserResponse = sendPlaywrightOnlyRequest(
                url,
                queryParams,
                query,
                phase,
                context,
                networkAudit,
                httpMethod
            );
            if (browserResponse != null) {
                return browserResponse;
            }
            log.info("Playwright browser request failed for search page, falling back to HTTP fetcher: {}", url);
            return sendHtmlRequest(url, queryParams, query, phase, context, networkAudit, httpMethod, false);
        }
        return sendHtmlRequest(url, queryParams, query, phase, context, networkAudit, httpMethod, true);
    }

    private HtmlResponse sendSearchPageRequest(String url,
                                               Map<String, String> queryParams,
                                               String query,
                                               String phase,
                                               WebSearchRequestContext context,
                                               List<Map<String, Object>> networkAudit,
                                               String httpMethod,
                                               String siteSearchMode) throws Exception {
        return sendSearchPageRequestWithMode(
            url,
            queryParams,
            query,
            phase,
            context,
            networkAudit,
            httpMethod,
            siteSearchMode,
            siteSearchFallbackToJava(),
            "site_search"
        );
    }

    private HtmlResponse sendSearchPageRequestWithMode(String url,
                                                       Map<String, String> queryParams,
                                                       String query,
                                                       String phase,
                                                       WebSearchRequestContext context,
                                                       List<Map<String, Object>> networkAudit,
                                                       String httpMethod,
                                                       String mode,
                                                       boolean fallbackToJava,
                                                       String modeLabel) throws Exception {
        String method = firstNonBlank(httpMethod, "GET").toUpperCase(Locale.ROOT);
        if (modeBrowserFirst(mode)) {
            HtmlResponse browserResponse = sendPlaywrightOnlyRequest(
                url,
                queryParams,
                query,
                phase,
                context,
                networkAudit,
                method
            );
            if (browserResponse != null) {
                return browserResponse;
            }
            if (!fallbackToJava) {
                throw new IOException(modeLabel + " browser request failed and Java fallback is disabled");
            }
            log.info("{} browser request failed for phase={} url={}, falling back to Java fetcher",
                modeLabel,
                phase,
                urlWithQueryParamsForPhase(url, queryParams, phase));
        }
        return sendHtmlRequest(url, queryParams, query, phase, context, networkAudit, method, false);
    }

    private HtmlResponse sendPlaywrightOnlyRequest(String url,
                                                   Map<String, String> queryParams,
                                                   String query,
                                                   String phase,
                                                   WebSearchRequestContext context,
                                                   List<Map<String, Object>> networkAudit,
                                                   String httpMethod) throws Exception {
        if (!isAllowedUrl(url, context)) {
            addAudit(networkAudit, query, url, phase, null, 0, 0, "BLOCKED", "domain not allowed");
            throw new IllegalArgumentException("Web search target domain is not allowed: " + hostOf(url));
        }
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            WebSearchToolProperties.ProxyConfig proxy = chooseProxy(context, attempt);
            long startedAt = System.currentTimeMillis();
            boolean rateAcquired = false;
            try {
                rateAcquired = acquireRateSlot();
                HtmlResponse browserResponse = tryPlaywrightRequest(
                    url,
                    queryParams,
                    query,
                    phase,
                    context,
                    proxy,
                    startedAt,
                    networkAudit,
                    firstNonBlank(httpMethod, "GET").toUpperCase(Locale.ROOT)
                );
                if (browserResponse != null) {
                    markProxySuccess(proxy);
                    return browserResponse;
                }
            } catch (Exception ex) {
                lastException = ex;
                markProxyFailure(proxy);
                long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                addAudit(networkAudit, query, url, phase, proxyId(proxy), 0, durationMs, "FAILED", ex.getMessage());
                logWebSearchAudit(query, url, phase, proxy, 0, durationMs, ex.getMessage());
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            } finally {
                if (rateAcquired) {
                    rateSemaphore.release();
                }
            }
        }
        if (lastException != null) {
            log.warn("Playwright browser request failed for phase={} url={} error={}", phase, url, lastException.getMessage());
        }
        return null;
    }

    private HtmlResponse sendHtmlRequest(String url,
                                         Map<String, String> queryParams,
                                         String query,
                                         String phase,
                                         WebSearchRequestContext context,
                                         List<Map<String, Object>> networkAudit,
                                         String httpMethod) throws Exception {
        return sendHtmlRequest(url, queryParams, query, phase, context, networkAudit, httpMethod, true);
    }

    private HtmlResponse sendHtmlRequest(String url,
                                         Map<String, String> queryParams,
                                         String query,
                                         String phase,
                                         WebSearchRequestContext context,
                                         List<Map<String, Object>> networkAudit,
                                         String httpMethod,
                                         boolean allowBrowserAttempt) throws Exception {
        if (!isAllowedUrl(url, context)) {
            addAudit(networkAudit, query, url, phase, null, 0, 0, "BLOCKED", "domain not allowed");
            throw new IllegalArgumentException("Web search target domain is not allowed: " + hostOf(url));
        }
        String method = firstNonBlank(httpMethod, "GET").toUpperCase(Locale.ROOT);
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            WebSearchToolProperties.ProxyConfig proxy = chooseProxy(context, attempt);
            long startedAt = System.currentTimeMillis();
            boolean rateAcquired = false;
            try {
                rateAcquired = acquireRateSlot();
                String cookieKey = cookieKey(context, proxy);
                Map<String, String> cookies = readCookies(cookieKey);
                HtmlResponse browserResponse = null;
                if (allowBrowserAttempt && !shouldUseRawQueryParams(phase, method)) {
                    browserResponse = tryPlaywrightRequest(
                        url,
                        queryParams,
                        query,
                        phase,
                        context,
                        proxy,
                        startedAt,
                        networkAudit,
                        method
                    );
                    if (browserResponse != null) {
                        markProxySuccess(proxy);
                        return browserResponse;
                    }
                }
                RawHttpResponse rawResponse = null;
                org.jsoup.Connection.Response response = null;
                int statusCode;
                String body;
                String responseUrl;
                if (shouldUseRawQueryParams(phase, method)) {
                    rawResponse = executeRawGet(urlWithRawQueryParams(url, queryParams), context, phase, proxy, cookies);
                    updateCookies(cookieKey, rawResponse.cookies());
                    statusCode = rawResponse.statusCode();
                    body = rawResponse.body();
                    responseUrl = rawResponse.url();
                } else {
                    org.jsoup.Connection connection = buildJsoupConnection(url, queryParams, phase, context, proxy, method);
                    if (!cookies.isEmpty()) {
                        connection.cookies(cookies);
                    }
                    response = connection.execute();
                    updateCookies(cookieKey, response.cookies());
                    statusCode = response.statusCode();
                    body = response.body();
                    responseUrl = response.url().toString();
                }
                long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                addAudit(networkAudit, query, responseUrl, phase, proxyId(proxy), statusCode,
                    durationMs, "OK", null);
                logWebSearchAudit(query, responseUrl, phase, proxy, statusCode, durationMs, null);
                if (shouldRetry(statusCode, body)) {
                    markProxyFailure(proxy);
                    if (attempt < maxAttempts) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    throw new IOException("HTTP " + statusCode + " from " + hostOf(url));
                }
                markProxySuccess(proxy);
                return rawResponse == null
                    ? new HtmlResponse(statusCode, response.parse())
                    : new HtmlResponse(statusCode, Jsoup.parse(body, responseUrl));
            } catch (Exception ex) {
                lastException = ex;
                markProxyFailure(proxy);
                long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                addAudit(networkAudit, query, url, phase, proxyId(proxy), 0, durationMs, "FAILED", ex.getMessage());
                logWebSearchAudit(query, url, phase, proxy, 0, durationMs, ex.getMessage());
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            } finally {
                if (rateAcquired) {
                    rateSemaphore.release();
                }
            }
        }
        throw lastException == null ? new IOException("web search request failed") : lastException;
    }

    private org.jsoup.Connection buildJsoupConnection(String url,
                                                      Map<String, String> queryParams,
                                                      String phase,
                                                      WebSearchRequestContext context,
                                                      WebSearchToolProperties.ProxyConfig proxy,
                                                      String httpMethod) {
        String requestUrl = shouldUseRawQueryParams(phase, httpMethod)
            ? urlWithRawQueryParams(url, queryParams)
            : url;
        org.jsoup.Connection connection = Jsoup.connect(requestUrl)
            .timeout(Math.max(0, properties.getTimeoutMs()))
            .maxBodySize(Math.max(1024, properties.getPageMaxBytes()))
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .followRedirects(true);
        if ("POST".equalsIgnoreCase(httpMethod)) {
            connection.method(org.jsoup.Connection.Method.POST);
        }
        if (queryParams != null && !queryParams.isEmpty() && !shouldUseRawQueryParams(phase, httpMethod)) {
            connection.data(queryParams);
        }
        applyBrowserHeaders(connection, requestUrl, phase, context, proxy);
        applyProxy(connection, proxy);
        return connection;
    }

    private RawHttpResponse executeRawGet(String requestUrl,
                                          WebSearchRequestContext context,
                                          String phase,
                                          WebSearchToolProperties.ProxyConfig proxy,
                                          Map<String, String> cookies) throws IOException {
        URI baseUri = baseUri(requestUrl);
        String scheme = firstNonBlank(baseUri.getScheme(), "http").toLowerCase(Locale.ROOT);
        String host = baseUri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("raw GET target host is missing: " + requestUrl);
        }
        int port = baseUri.getPort() > 0 ? baseUri.getPort() : ("https".equals(scheme) ? 443 : 80);
        boolean https = "https".equals(scheme);
        if (https && proxy != null && "HTTP".equalsIgnoreCase(firstNonBlank(proxy.getType(), ""))) {
            throw new IOException("raw HTTPS GET through HTTP proxy is not supported");
        }

        try (Socket socket = openRawSocket(host, port, https, proxy)) {
            socket.setSoTimeout(Math.max(0, properties.getTimeoutMs()));
            OutputStream out = socket.getOutputStream();
            String requestTarget = requestTarget(requestUrl, baseUri, proxy);
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(requestTarget).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(hostHeader(host, port, https)).append("\r\n");
            request.append("User-Agent: ").append(selectUserAgent(context)).append("\r\n");
            request.append("Accept: ").append(firstNonBlank(properties.getBrowser().getAccept(), "*/*")).append("\r\n");
            request.append("Accept-Language: ").append(firstNonBlank(properties.getBrowser().getAcceptLanguage(), "en-US,en;q=0.9")).append("\r\n");
            request.append("Connection: close\r\n");
            String referer = firstNonBlank(context.referer(), properties.getBrowser().getReferer());
            if (referer != null && !referer.isBlank()) {
                request.append("Referer: ").append(referer).append("\r\n");
            }
            String cookieHeader = cookieHeader(cookies);
            if (!cookieHeader.isBlank()) {
                request.append("Cookie: ").append(cookieHeader).append("\r\n");
            }
            if (proxy != null && proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
                String token = Base64.getEncoder().encodeToString(
                    (proxy.getUsername() + ":" + firstNonBlank(proxy.getPassword(), "")).getBytes(StandardCharsets.UTF_8));
                request.append("Proxy-Authorization: Basic ").append(token).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            byte[] bytes = readAll(socket.getInputStream());
            String raw = new String(bytes, StandardCharsets.UTF_8);
            int headerEnd = raw.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                throw new IOException("raw GET returned an invalid HTTP response for " + requestUrl);
            }
            String headerText = raw.substring(0, headerEnd);
            String body = raw.substring(headerEnd + 4);
            int statusCode = statusCode(headerText);
            if (isChunked(headerText)) {
                body = decodeChunkedBody(body);
            }
            Map<String, String> responseCookies = responseCookies(headerText);
            log.debug("Raw site-search GET completed phase={} url={} statusCode={}", phase, requestUrl, statusCode);
            return new RawHttpResponse(statusCode, body, responseCookies, requestUrl);
        }
    }

    private Socket openRawSocket(String host,
                                 int port,
                                 boolean https,
                                 WebSearchToolProperties.ProxyConfig proxy) throws IOException {
        int timeoutMs = Math.max(0, properties.getTimeoutMs());
        Socket socket;
        if (proxy != null && proxy.getHost() != null && !proxy.getHost().isBlank() && proxy.getPort() > 0) {
            String type = firstNonBlank(proxy.getType(), "HTTP").toUpperCase(Locale.ROOT);
            if (type.contains("SOCKS")) {
                socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxy.getHost(), proxy.getPort())));
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                return https
                    ? ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true)
                    : socket;
            }
            socket = new Socket();
            socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeoutMs);
            return socket;
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        return https
            ? ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true)
            : socket;
    }

    private URI baseUri(String requestUrl) {
        int queryStart = requestUrl.indexOf('?');
        String baseUrl = queryStart >= 0 ? requestUrl.substring(0, queryStart) : requestUrl;
        return URI.create(baseUrl);
    }

    private String requestTarget(String requestUrl, URI baseUri, WebSearchToolProperties.ProxyConfig proxy) {
        if (proxy != null
            && "HTTP".equalsIgnoreCase(firstNonBlank(proxy.getType(), ""))
            && "http".equalsIgnoreCase(firstNonBlank(baseUri.getScheme(), "http"))) {
            return requestUrl;
        }
        int queryStart = requestUrl.indexOf('?');
        String rawPath = firstNonBlank(baseUri.getRawPath(), "/");
        if (rawPath.isBlank()) {
            rawPath = "/";
        }
        return queryStart >= 0 ? rawPath + requestUrl.substring(queryStart) : rawPath;
    }

    private String hostHeader(String host, int port, boolean https) {
        int defaultPort = https ? 443 : 80;
        return port == defaultPort ? host : host + ":" + port;
    }

    private String cookieHeader(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        return cookies.entrySet().stream()
            .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
            .map(entry -> entry.getKey() + "=" + firstNonBlank(entry.getValue(), ""))
            .reduce((left, right) -> left + "; " + right)
            .orElse("");
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        return inputStream.readAllBytes();
    }

    private int statusCode(String headerText) throws IOException {
        String firstLine = headerText.lines().findFirst().orElse("");
        Matcher matcher = Pattern.compile("HTTP/\\S+\\s+(\\d{3})").matcher(firstLine);
        if (!matcher.find()) {
            throw new IOException("raw GET returned an invalid status line: " + firstLine);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private Map<String, String> responseCookies(String headerText) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String line : headerText.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || !"set-cookie".equalsIgnoreCase(line.substring(0, colon).trim())) {
                continue;
            }
            String cookie = line.substring(colon + 1).trim();
            int semicolon = cookie.indexOf(';');
            String pair = semicolon >= 0 ? cookie.substring(0, semicolon) : cookie;
            int equals = pair.indexOf('=');
            if (equals > 0) {
                cookies.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return cookies;
    }

    private boolean isChunked(String headerText) {
        return headerText != null
            && Pattern.compile("(?im)^transfer-encoding\\s*:\\s*.*\\bchunked\\b").matcher(headerText).find();
    }

    private String decodeChunkedBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        StringBuilder decoded = new StringBuilder();
        int position = 0;
        while (position < body.length()) {
            int lineEnd = body.indexOf("\r\n", position);
            if (lineEnd < 0) {
                break;
            }
            String sizeLine = body.substring(position, lineEnd).trim();
            int extension = sizeLine.indexOf(';');
            if (extension >= 0) {
                sizeLine = sizeLine.substring(0, extension).trim();
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException ex) {
                return body;
            }
            position = lineEnd + 2;
            if (size <= 0) {
                break;
            }
            int chunkEnd = Math.min(body.length(), position + size);
            decoded.append(body, position, chunkEnd);
            position = chunkEnd;
            if (position + 2 <= body.length() && body.startsWith("\r\n", position)) {
                position += 2;
            }
        }
        return decoded.toString();
    }

    private HtmlResponse tryPlaywrightRequest(String url,
                                              Map<String, String> queryParams,
                                              String query,
                                              String phase,
                                              WebSearchRequestContext context,
                                              WebSearchToolProperties.ProxyConfig proxy,
                                              long startedAt,
                                              List<Map<String, Object>> networkAudit,
                                              String httpMethod) {
        if (!browserRenderingEnabled()) {
            return null;
        }
        String method = firstNonBlank(httpMethod, "GET").toUpperCase(Locale.ROOT);
        String targetUrl = "POST".equals(method)
            ? url
            : urlWithQueryParamsForPhase(url, queryParams, phase);
        try {
            HtmlResponse response = executePlaywrightRequest(targetUrl, queryParams, method, context, proxy);
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            addAudit(networkAudit, query, targetUrl, phase, proxyId(proxy), response.statusCode(), durationMs,
                "OK", "playwright browser");
            logWebSearchAudit(query, targetUrl, phase, proxy, response.statusCode(), durationMs, null);
            return response;
        } catch (Exception ex) {
            log.warn("Playwright web_search request failed url={} error={}", targetUrl, ex.getMessage());
            return null;
        }
    }

    private boolean browserRenderingEnabled() {
        WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
        return browser != null && browser.isEnabled();
    }

    private HtmlResponse executePlaywrightRequest(String targetUrl,
                                                  Map<String, String> queryParams,
                                                  String httpMethod,
                                                  WebSearchRequestContext context,
                                                  WebSearchToolProperties.ProxyConfig proxy) {
        WebSearchToolProperties.BrowserProperties browserProperties = properties.getBrowser();
        int timeoutMs = Math.max(0, browserProperties.getNavigationTimeoutMs());
        var launchOptions = playwrightSupport.headlessLaunchOptions(timeoutMs, playwrightProxyConfig(proxy));
        try (Playwright playwright = playwrightSupport.createPlaywright(playwrightBrowserConfig(browserProperties));
             Browser browser = playwright.chromium().launch(launchOptions);
             BrowserContext browserContext = browser.newContext(playwrightContextOptions(context))) {
            Page page = browserContext.newPage();
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);
            com.microsoft.playwright.Response response;
            if ("POST".equalsIgnoreCase(httpMethod)) {
                page.setContent(autoSubmitFormHtml(targetUrl, queryParams, "POST"));
                page.locator("#f").evaluate("form => form.submit()");
                playwrightSupport.waitForNetworkIdle(page, timeoutMs,
                    "Playwright page did not reach networkidle before content extraction");
                response = null;
            } else {
                response = page.navigate(targetUrl, new Page.NavigateOptions()
                    .setTimeout(timeoutMs)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                playwrightSupport.waitForNetworkIdle(page, timeoutMs,
                    "Playwright page did not reach networkidle before content extraction");
            }
            String html = page.content();
            if (html == null || html.isBlank()) {
                throw new IllegalStateException("Playwright returned empty page content");
            }
            return new HtmlResponse(response == null ? 200 : response.status(), Jsoup.parse(html, page.url()));
        }
    }

    private PlaywrightBrowserSupport.BrowserConfig playwrightBrowserConfig(WebSearchToolProperties.BrowserProperties browserProperties) {
        return new PlaywrightBrowserSupport.BrowserConfig(
            browserProperties == null ? null : browserProperties.getBrowsersPath(),
            browserProperties != null && browserProperties.isSkipBrowserDownload()
        );
    }

    private PlaywrightBrowserSupport.ProxyConfig playwrightProxyConfig(WebSearchToolProperties.ProxyConfig proxy) {
        return new PlaywrightBrowserSupport.ProxyConfig(
            properties.getProxyPool() != null && properties.getProxyPool().isEnabled(),
            proxy == null ? null : proxy.getType(),
            proxy == null ? null : proxy.getHost(),
            proxy == null ? 0 : proxy.getPort(),
            proxy == null ? null : proxy.getUsername(),
            proxy == null ? null : proxy.getPassword()
        );
    }

    private Browser.NewContextOptions playwrightContextOptions(WebSearchRequestContext context) {
        WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", firstNonBlank(browser.getAccept(), "*/*"));
        headers.put("Accept-Language", firstNonBlank(browser.getAcceptLanguage(), "en-US,en;q=0.9"));
        String referer = firstNonBlank(context.referer(), browser.getReferer());
        if (referer != null && !referer.isBlank()) {
            headers.put("Referer", referer);
        }
        if (browser.getHeaders() != null) {
            headers.putAll(browser.getHeaders());
        }
        return new Browser.NewContextOptions()
            .setUserAgent(selectUserAgent(context))
            .setLocale(browserLanguage(browser.getAcceptLanguage()))
            .setViewportSize(1365, 768)
            .setIgnoreHTTPSErrors(true)
            .setExtraHTTPHeaders(headers);
    }

    private String autoSubmitFormHtml(String actionUrl, Map<String, String> parameters, String method) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><body>");
        html.append("<form id=\"f\" action=\"").append(escapeHtmlAttribute(actionUrl)).append("\" method=\"")
            .append(escapeHtmlAttribute(firstNonBlank(method, "POST"))).append("\">");
        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                html.append("<input type=\"hidden\" name=\"")
                    .append(escapeHtmlAttribute(entry.getKey()))
                    .append("\" value=\"")
                    .append(escapeHtmlAttribute(firstNonBlank(entry.getValue(), "")))
                    .append("\">");
            }
        }
        html.append("</form></body></html>");
        return html.toString();
    }

    private String escapeHtmlAttribute(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private String browserLanguage(String acceptLanguage) {
        String value = firstNonBlank(acceptLanguage, "en-US");
        int separator = value.indexOf(',');
        return separator > 0 ? value.substring(0, separator).trim() : value.trim();
    }

    private String urlWithQueryParams(String url, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        builder.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (!first) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(firstNonBlank(entry.getValue(), ""), StandardCharsets.UTF_8));
            first = false;
        }
        return builder.toString();
    }

    private String urlWithQueryParamsForPhase(String url, Map<String, String> queryParams, String phase) {
        return shouldUseRawQueryParams(phase, "GET")
            ? urlWithRawQueryParams(url, queryParams)
            : urlWithQueryParams(url, queryParams);
    }

    private boolean shouldUseRawQueryParams(String phase, String httpMethod) {
        String method = firstNonBlank(httpMethod, "GET").toUpperCase(Locale.ROOT);
        return "GET".equals(method)
            && phase != null
            && phase.startsWith("site_search")
            && !"site_search_known".equals(phase);
    }

    private String urlWithRawQueryParams(String url, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        builder.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (!first) {
                builder.append('&');
            }
            builder.append(entry.getKey().trim());
            builder.append('=');
            builder.append(rawQueryValue(firstNonBlank(entry.getValue(), "")));
            first = false;
        }
        return builder.toString();
    }

    private String rawQueryValue(String value) {
        return value == null ? "" : value;
    }

    private void applyBrowserHeaders(org.jsoup.Connection connection,
                                     String url,
                                     String phase,
                                     WebSearchRequestContext context,
                                     WebSearchToolProperties.ProxyConfig proxy) {
        WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
        String userAgent = selectUserAgent(context);
        connection.userAgent(userAgent);
        if (!browser.isEnabled()) {
            return;
        }
        connection.header("Accept", browser.getAccept());
        connection.header("Accept-Language", browser.getAcceptLanguage());
        connection.header("Accept-Encoding", "gzip, deflate, br");
        connection.header("Cache-Control", "no-cache");
        connection.header("Pragma", "no-cache");
        connection.header("Upgrade-Insecure-Requests", "1");
        connection.header("DNT", "1");
        connection.header("Sec-Fetch-Dest", "document");
        connection.header("Sec-Fetch-Mode", "navigate");
        connection.header("Sec-Fetch-Site", "search".equals(phase) ? "none" : "cross-site");
        connection.header("Sec-Fetch-User", "?1");
        connection.header("sec-ch-ua", browser.getSecChUa());
        connection.header("sec-ch-ua-mobile", browser.getSecChUaMobile());
        connection.header("sec-ch-ua-platform", browser.getSecChUaPlatform());
        String referer = firstNonBlank(context.referer(), browser.getReferer());
        if (referer != null && !referer.isBlank()) {
            connection.referrer(referer);
        }
        browser.getHeaders().forEach(connection::header);
        if (proxy != null && proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                (proxy.getUsername() + ":" + firstNonBlank(proxy.getPassword(), "")).getBytes(StandardCharsets.UTF_8));
            connection.header("Proxy-Authorization", "Basic " + token);
        }
        connection.header("X-ChatChat-Search-Task", context.taskId() == null ? "" : context.taskId());
        connection.header("X-ChatChat-TLS-Profile", firstNonBlank(browser.getTlsFingerprintProfile(), "jdk-default"));
    }

    private void applyProxy(org.jsoup.Connection connection, WebSearchToolProperties.ProxyConfig proxyConfig) {
        Proxy proxy = toProxy(proxyConfig);
        if (proxy != null) {
            connection.proxy(proxy);
        }
    }

    private Proxy toProxy(WebSearchToolProperties.ProxyConfig config) {
        if (!properties.getProxyPool().isEnabled()
            || config == null
            || config.getHost() == null
            || config.getHost().isBlank()
            || config.getPort() <= 0) {
            return null;
        }
        String type = config.getType() == null ? "HTTP" : config.getType().trim().toUpperCase(Locale.ROOT);
        Proxy.Type proxyType = "SOCKS5".equals(type) || "SOCKS".equals(type) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(proxyType, new InetSocketAddress(config.getHost().trim(), config.getPort()));
    }

    private WebSearchToolProperties.ProxyConfig chooseProxy(WebSearchRequestContext context, int attempt) {
        if (!properties.getProxyPool().isEnabled() || properties.getProxyPool().getProxies().isEmpty()) {
            return null;
        }
        List<WebSearchToolProperties.ProxyConfig> candidates = properties.getProxyPool().getProxies().stream()
            .filter(proxy -> matchesProxyScope(proxy, context))
            .toList();
        if (candidates.isEmpty()) {
            candidates = properties.getProxyPool().getProxies();
        }
        long now = System.currentTimeMillis();
        int size = candidates.size();
        int offset = Math.floorMod(proxyCursor.getAndIncrement() + Math.max(0, attempt - 1), size);
        for (int index = 0; index < size; index++) {
            WebSearchToolProperties.ProxyConfig candidate = candidates.get((offset + index) % size);
            ProxyRuntimeState state = proxyState(candidate);
            if (state.openUntilMs <= now) {
                state.requestCount.incrementAndGet();
                return candidate;
            }
        }
        WebSearchToolProperties.ProxyConfig fallback = candidates.get(offset % size);
        proxyState(fallback).requestCount.incrementAndGet();
        return fallback;
    }

    private boolean matchesProxyScope(WebSearchToolProperties.ProxyConfig proxy, WebSearchRequestContext context) {
        boolean tenantMatches = proxy.getTenantIds() == null
            || proxy.getTenantIds().isEmpty()
            || (context.tenantId() != null && proxy.getTenantIds().contains(context.tenantId()));
        boolean taskMatches = proxy.getTaskIds() == null
            || proxy.getTaskIds().isEmpty()
            || (context.taskId() != null && proxy.getTaskIds().contains(context.taskId()));
        String pool = properties.getProxyPool().getDefaultPool();
        boolean poolMatches = pool == null || pool.isBlank() || pool.equalsIgnoreCase(firstNonBlank(proxy.getPool(), "default"));
        return tenantMatches && taskMatches && poolMatches;
    }

    private ProxyRuntimeState proxyState(WebSearchToolProperties.ProxyConfig proxy) {
        String id = proxyId(proxy);
        synchronized (proxyStates) {
            return proxyStates.computeIfAbsent(id, ignored -> new ProxyRuntimeState());
        }
    }

    private void markProxySuccess(WebSearchToolProperties.ProxyConfig proxy) {
        if (proxy == null) {
            return;
        }
        ProxyRuntimeState state = proxyState(proxy);
        state.failureCount.set(0);
        state.openUntilMs = 0L;
    }

    private void markProxyFailure(WebSearchToolProperties.ProxyConfig proxy) {
        if (proxy == null) {
            return;
        }
        ProxyRuntimeState state = proxyState(proxy);
        int failures = state.failureCount.incrementAndGet();
        if (failures >= 2) {
            state.openUntilMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        }
    }

    private boolean acquireRateSlot() throws InterruptedException {
        if (!properties.getRateLimit().isEnabled()) {
            return false;
        }
        resetDailyWindowIfNeeded();
        int dailyLimit = Math.max(1, properties.getRateLimit().getDailyLimit());
        if (dailyCalls.incrementAndGet() > dailyLimit) {
            throw new IllegalStateException("web_search daily call limit exceeded: " + dailyLimit);
        }
        rateSemaphore.acquire();
        try {
            double qps = Math.max(0.1d, properties.getRateLimit().getQps());
            long minIntervalMs = Math.max(1L, Math.round(1000.0d / qps));
            synchronized (rateLock) {
                long now = System.currentTimeMillis();
                long waitMs = lastRequestAtMs + minIntervalMs - now;
                if (waitMs > 0) {
                    Thread.sleep(waitMs);
                }
                lastRequestAtMs = System.currentTimeMillis();
            }
            return true;
        } catch (InterruptedException | RuntimeException ex) {
            rateSemaphore.release();
            throw ex;
        }
    }

    private void resetDailyWindowIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(dailyWindow)) {
            synchronized (rateLock) {
                if (!today.equals(dailyWindow)) {
                    dailyWindow = today;
                    dailyCalls.set(0);
                }
            }
        }
    }

    private boolean shouldRetry(int statusCode, String body) {
        if (properties.getRetry().getRetryStatusCodes().contains(statusCode)) {
            return true;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return properties.getRetry().getRetryBodyKeywords().stream()
            .filter(keyword -> keyword != null && !keyword.isBlank())
            .map(keyword -> keyword.toLowerCase(Locale.ROOT))
            .anyMatch(lower::contains);
    }

    private void sleepBackoff(int attempt) throws InterruptedException {
        long backoffMs = Math.max(0L, properties.getRetry().getBackoffMs());
        if (backoffMs > 0) {
            Thread.sleep(backoffMs * Math.max(1, attempt));
        }
    }

    private WebSearchRequestContext requestContext(ToolInput input, String query) {
        Map<String, Object> parameters = input == null || input.getParameters() == null
            ? Map.of()
            : input.getParameters();
        return new WebSearchRequestContext(
            query,
            firstNonBlank(stringValue(parameters.get("tenantId")), stringValue(parameters.get("tenant_id"))),
            firstNonBlank(stringValue(parameters.get("userId")), stringValue(parameters.get("user_id"))),
            stringList(firstObject(parameters.get("roles"), parameters.get("role"))),
            firstNonBlank(stringValue(parameters.get("sourceTaskId")), stringValue(parameters.get("taskId"))),
            stringValue(parameters.get("agentId")),
            stringValue(parameters.get("referer")),
            stringList(firstObject(parameters.get("allowedDomains"), parameters.get("allowed_domains"), parameters.get("allowDomains"))),
            stringList(firstObject(parameters.get("blockedDomains"), parameters.get("blocked_domains"), parameters.get("denyDomains")))
        );
    }

    private String selectUserAgent(WebSearchRequestContext context) {
        List<String> userAgents = properties.getBrowser().getUserAgents();
        if (userAgents != null && !userAgents.isEmpty()) {
            int index = Math.abs((context.query() + ":" + stringValue(context.taskId())).hashCode()) % userAgents.size();
            return userAgents.get(index);
        }
        return firstNonBlank(properties.getUserAgent(), "Mozilla/5.0");
    }

    private String cookieKey(WebSearchRequestContext context, WebSearchToolProperties.ProxyConfig proxy) {
        if (!properties.getCookie().isEnabled()) {
            return "disabled";
        }
        String isolation = firstNonBlank(properties.getCookie().getIsolation(), "proxy_task").toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        if (isolation.contains("proxy")) {
            parts.add(proxyId(proxy));
        }
        if (isolation.contains("tenant")) {
            parts.add(firstNonBlank(context.tenantId(), "tenant-default"));
        }
        if (isolation.contains("task")) {
            parts.add(firstNonBlank(context.taskId(), "task-default"));
        }
        if (parts.isEmpty()) {
            parts.add("global");
        }
        return String.join(":", parts);
    }

    private Map<String, String> readCookies(String key) {
        if (!properties.getCookie().isEnabled()) {
            return Map.of();
        }
        synchronized (cookieLock) {
            return new LinkedHashMap<>(cookieJar.getOrDefault(key, Map.of()));
        }
    }

    private void updateCookies(String key, Map<String, String> cookies) {
        if (!properties.getCookie().isEnabled() || cookies == null || cookies.isEmpty()) {
            return;
        }
        synchronized (cookieLock) {
            Map<String, String> values = new LinkedHashMap<>(cookieJar.getOrDefault(key, Map.of()));
            values.putAll(cookies);
            cookieJar.put(key, values);
            saveCookies();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadCookies() {
        if (!properties.getCookie().isEnabled() || !properties.getCookie().isPersist()) {
            return;
        }
        try {
            Path path = Path.of(properties.getCookie().getStorePath());
            if (!Files.exists(path)) {
                return;
            }
            Map<String, Map<String, String>> loaded = objectMapper.readValue(path.toFile(), Map.class);
            cookieJar.clear();
            loaded.forEach((key, value) -> cookieJar.put(key, new LinkedHashMap<>(value)));
        } catch (Exception ex) {
            log.warn("Failed to load web_search cookies: {}", ex.getMessage());
        }
    }

    private void saveCookies() {
        if (!properties.getCookie().isPersist()) {
            return;
        }
        try {
            Path path = Path.of(properties.getCookie().getStorePath());
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(path.toFile(), cookieJar);
        } catch (Exception ex) {
            log.warn("Failed to persist web_search cookies: {}", ex.getMessage());
        }
    }

    private boolean isAllowedUrl(String url, WebSearchRequestContext context) {
        String host = hostOf(url);
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (matchesDomain(normalizedHost, context == null ? List.of() : context.blockedDomains())) {
            return false;
        }
        List<String> requestAllowList = context == null ? List.of() : context.allowedDomains();
        if (requestAllowList != null && !requestAllowList.isEmpty()) {
            return matchesDomain(normalizedHost, requestAllowList);
        }
        if (!properties.getAllowList().isEnabled()) {
            return true;
        }
        return matchesDomain(normalizedHost, properties.getAllowList().getDomains());
    }

    private boolean matchesDomain(String normalizedHost, List<String> domains) {
        if (normalizedHost == null || normalizedHost.isBlank() || domains == null || domains.isEmpty()) {
            return false;
        }
        return domains.stream()
            .filter(domain -> domain != null && !domain.isBlank())
            .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
            .map(domain -> domain.startsWith("http://") || domain.startsWith("https://") ? hostOf(domain) : domain)
            .filter(domain -> domain != null && !domain.isBlank())
            .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
    }

    private List<Map<String, Object>> filterAllowedResults(List<Map<String, Object>> results,
                                                           WebSearchRequestContext context,
                                                           List<Map<String, Object>> networkAudit,
                                                           String query,
                                                           String phase) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> result : results) {
            String url = stringValue(result.get("url"));
            if (url == null || url.isBlank() || isAllowedUrl(url, context)) {
                filtered.add(result);
            } else {
                addAudit(networkAudit, query, url, phase, null, 0, 0, "BLOCKED", "domain not allowed");
            }
        }
        return filtered;
    }

    private Map<String, Object> requestContextMap(WebSearchRequestContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (context == null) {
            return values;
        }
        values.put("tenantId", firstNonBlank(context.tenantId(), "default"));
        values.put("userId", firstNonBlank(context.userId(), "anonymous"));
        values.put("roles", context.roles() == null ? List.of() : context.roles());
        values.put("taskId", context.taskId());
        values.put("agentId", context.agentId());
        values.put("allowedDomains", context.allowedDomains() == null ? List.of() : context.allowedDomains());
        values.put("blockedDomains", context.blockedDomains() == null ? List.of() : context.blockedDomains());
        return values;
    }

    private Map<String, Object> governanceMap(WebSearchRequestContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("domainPolicy", Map.of(
            "globalAllowListEnabled", properties.getAllowList().isEnabled(),
            "requestAllowListEnabled", context != null && context.allowedDomains() != null && !context.allowedDomains().isEmpty(),
            "requestBlockListEnabled", context != null && context.blockedDomains() != null && !context.blockedDomains().isEmpty()
        ));
        values.put("auditEnabled", properties.getAudit().isEnabled());
        values.put("contractVersion", CONTRACT_VERSION);
        return values;
    }

    private List<Map<String, Object>> auditForResult(List<Map<String, Object>> audit, WebSearchRequestContext context) {
        if (audit == null || audit.isEmpty()) {
            return List.of();
        }
        return audit.stream()
            .map(item -> {
                Map<String, Object> copy = new LinkedHashMap<>(item);
                copy.put("tenantId", context == null ? null : firstNonBlank(context.tenantId(), "default"));
                copy.put("userId", context == null ? null : firstNonBlank(context.userId(), "anonymous"));
                copy.put("roles", context == null || context.roles() == null ? List.of() : context.roles());
                copy.put("taskId", context == null ? null : context.taskId());
                copy.put("agentId", context == null ? null : context.agentId());
                return copy;
            })
            .toList();
    }

    private String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception ex) {
            return null;
        }
    }

    private String originOf(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = firstNonBlank(uri.getScheme(), "https").toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort();
            StringBuilder builder = new StringBuilder(scheme).append("://").append(host);
            if (port > 0 && !(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) {
                builder.append(':').append(port);
            }
            return builder.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private String proxyId(WebSearchToolProperties.ProxyConfig proxy) {
        if (proxy == null) {
            return "direct";
        }
        if (proxy.getId() != null && !proxy.getId().isBlank()) {
            return proxy.getId();
        }
        return firstNonBlank(proxy.getType(), "HTTP") + "://" + proxy.getHost() + ":" + proxy.getPort();
    }

    private void addAudit(List<Map<String, Object>> audit,
                          String query,
                          String url,
                          String phase,
                          String proxyId,
                          int statusCode,
                          long durationMs,
                          String outcome,
                          String errorMessage) {
        if (!properties.getAudit().isEnabled() || audit == null) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", java.time.Instant.now().toString());
        item.put("keyword", query);
        item.put("phase", phase);
        item.put("targetDomain", hostOf(url));
        item.put("statusCode", statusCode);
        item.put("durationMs", durationMs);
        item.put("outcome", outcome);
        item.put("errorMessage", errorMessage);
        audit.add(item);
    }

    private void logWebSearchAudit(String query,
                                   String url,
                                   String phase,
                                   WebSearchToolProperties.ProxyConfig proxy,
                                   int statusCode,
                                   long durationMs,
                                   String errorMessage) {
        if (!properties.getAudit().isEnabled()) {
            return;
        }
        log.info("Web search audit keyword={} phase={} domain={} proxyId={} statusCode={} durationMs={} error={}",
            query,
            phase,
            hostOf(url),
            proxyId(proxy),
            statusCode,
            durationMs,
            errorMessage);
    }

    /**
     * Returns whether is fetchable page url.
     *
     * @param url the url value
     * @return whether the condition is satisfied
     */
    private boolean isFetchablePageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String value = url.toLowerCase(Locale.ROOT);
        if (!isHttpUrl(value)) {
            return false;
        }
        return !(value.endsWith(".pdf")
            || value.endsWith(".doc")
            || value.endsWith(".docx")
            || value.endsWith(".xls")
            || value.endsWith(".xlsx")
            || value.endsWith(".zip")
            || value.endsWith(".rar")
            || value.endsWith(".7z"));
    }

    private boolean isSupportedResultUrl(String url) {
        return isHttpUrl(url);
    }

    private boolean isDocumentUrl(String url) {
        if (!isHttpUrl(url)) {
            return false;
        }
        String value = stripUrlQueryAndFragment(url).toLowerCase(Locale.ROOT);
        return value.endsWith(".pdf")
            || value.endsWith(".doc")
            || value.endsWith(".docx")
            || value.endsWith(".xls")
            || value.endsWith(".xlsx")
            || value.endsWith(".csv")
            || value.endsWith(".ppt")
            || value.endsWith(".pptx");
    }

    private String fileNameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) {
                return url;
            }
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception ex) {
            return url;
        }
    }

    private String stripUrlQueryAndFragment(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int cut = -1;
        if (query >= 0 && fragment >= 0) {
            cut = Math.min(query, fragment);
        } else if (query >= 0) {
            cut = query;
        } else if (fragment >= 0) {
            cut = fragment;
        }
        return cut >= 0 ? url.substring(0, cut) : url;
    }

    private boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String value = url.toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    /**
     * Performs the extract readable text operation.
     *
     * @param page the page value
     * @return the operation result
     */
    private String extractReadableText(Document page) {
        page.select("script,style,noscript,svg,canvas,iframe,header,footer,nav,aside,form").remove();
        Element main = first(
            page.selectFirst("main"),
            page.selectFirst("article"),
            page.selectFirst("[role=main]"),
            page.body()
        );
        return main == null ? "" : main.text().replaceAll("\\s+", " ").trim();
    }

    /**
     * Builds the text excerpt.
     *
     * @param text the text value
     * @param query the query value
     * @param maxChars the max chars value
     * @return the built text excerpt
     */
    private String buildTextExcerpt(String text, String query, int maxChars) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int limit = Math.max(500, Math.min(8000, maxChars));
        if (normalized.length() <= limit) {
            return normalized;
        }
        int start = bestExcerptStart(normalized, query, limit);
        int end = Math.min(normalized.length(), start + limit);
        String excerpt = normalized.substring(start, end).trim();
        if (start > 0) {
            excerpt = "..." + excerpt;
        }
        if (end < normalized.length()) {
            excerpt = excerpt + "...";
        }
        return excerpt;
    }

    /**
     * Performs the best excerpt start operation.
     *
     * @param text the text value
     * @param query the query value
     * @param maxChars the max chars value
     * @return the operation result
     */
    private int bestExcerptStart(String text, String query, int maxChars) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String term : queryTerms(query)) {
            int index = lowerText.indexOf(term.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                return Math.max(0, index - maxChars / 4);
            }
        }
        return 0;
    }

    /**
     * Queries the terms.
     *
     * @param query the query value
     * @return the operation result
     */
    private List<String> queryTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String term : query.trim().split("[\\s,\\uFF0C\\u3002\\uFF1B;:\\uFF1A\\u3001\\\\]+")) {
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        terms.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return terms;
    }

    /**
     * Parses the duck duck go results.
     *
     * @param document the document value
     * @param numResults the num results value
     * @return the parsed duck duck go results
     */
    private List<Map<String, Object>> parseDuckDuckGoResults(Document document, int numResults) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Element block : document.select(".result, .web-result")) {
            if (results.size() >= numResults) {
                break;
            }
            Element link = first(block.selectFirst("a.result__a"), block.selectFirst("a[href]"));
            if (link == null) {
                continue;
            }
            String title = link.text();
            String url = normalizeSearchUrl(link.attr("href"));
            if (title == null || title.isBlank() || url == null || url.isBlank()) {
                continue;
            }
            Element snippetElement = first(
                block.selectFirst(".result__snippet"),
                block.selectFirst(".snippet"),
                block.selectFirst(".result__body")
            );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", results.size() + 1);
            item.put("title", title);
            item.put("url", url);
            item.put("snippet", snippetElement == null ? "" : snippetElement.text());
            results.add(item);
        }

        if (results.isEmpty()) {
            for (Element link : document.select("a.result__a")) {
                if (results.size() >= numResults) {
                    break;
                }
                String title = link.text();
                String url = normalizeSearchUrl(link.attr("href"));
                if (title == null || title.isBlank() || url == null || url.isBlank()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", results.size() + 1);
                item.put("title", title);
                item.put("url", url);
                item.put("snippet", "");
                results.add(item);
            }
        }
        return results;
    }

    /**
     * Parses the bing results.
     *
     * @param document the document value
     * @param numResults the num results value
     * @return the parsed bing results
     */
    private List<Map<String, Object>> parseBingResults(Document document, int numResults) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Element block : document.select("li.b_algo")) {
            if (results.size() >= numResults) {
                break;
            }
            Element link = block.selectFirst("h2 a[href]");
            if (link == null) {
                continue;
            }
            String title = link.text();
            String url = normalizeSearchUrl(link.attr("href"));
            if (title == null || title.isBlank() || url == null || url.isBlank()) {
                continue;
            }
            Element snippetElement = first(block.selectFirst(".b_caption p"), block.selectFirst("p"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", results.size() + 1);
            item.put("title", title);
            item.put("url", url);
            item.put("snippet", snippetElement == null ? "" : snippetElement.text());
            results.add(item);
        }
        return results;
    }

    /**
     * Performs the first operation.
     *
     * @param elements the elements value
     * @return the operation result
     */
    private Element first(Element... elements) {
        for (Element element : elements) {
            if (element != null) {
                return element;
            }
        }
        return null;
    }

    /**
     * Normalizes the search url.
     *
     * @param href the href value
     * @return the operation result
     */
    private String normalizeSearchUrl(String href) {
        if (href == null || href.isBlank()) {
            return href;
        }
        String value = href.trim();
        try {
            if (value.startsWith("//")) {
                return "https:" + value;
            }
            if (value.startsWith("/l/?") || value.startsWith("https://duckduckgo.com/l/?")) {
                URI uri = value.startsWith("http")
                    ? URI.create(value)
                    : URI.create("https://duckduckgo.com" + value);
                String decoded = queryParam(uri.getRawQuery(), "uddg");
                if (decoded != null && !decoded.isBlank()) {
                    return decoded;
                }
            }
            return value;
        } catch (Exception ignored) {
            return value;
        }
    }

    /**
     * Queries the param.
     *
     * @param rawQuery the raw query value
     * @param name the name value
     * @return the operation result
     */
    private String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (!name.equals(key)) {
                continue;
            }
            String value = equals < 0 ? "" : part.substring(equals + 1);
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }

    private boolean containsAny(String value, List<String> candidates) {
        if (value == null || value.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return candidates.stream()
            .filter(candidate -> candidate != null && !candidate.isBlank())
            .map(candidate -> candidate.trim().toLowerCase(Locale.ROOT))
            .anyMatch(lower::contains);
    }

    private String normalizeComparableUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String authority = host;
            if (port > 0 && !(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) {
                authority = authority + ":" + port;
            }
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            String query = uri.getRawQuery();
            StringBuilder builder = new StringBuilder();
            if (!scheme.isBlank()) {
                builder.append(scheme).append("://");
            }
            builder.append(authority).append(path);
            if (query != null && !query.isBlank()) {
                builder.append('?').append(query);
            }
            return builder.toString();
        } catch (Exception ex) {
            int fragment = url.indexOf('#');
            return (fragment >= 0 ? url.substring(0, fragment) : url).trim();
        }
    }

    /**
     * Performs the string value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private List<Map<String, Object>> loggableSearchResults(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
            .limit(10)
            .map(result -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", result.get("title"));
                item.put("url", result.get("url"));
                item.put("snippet", truncateForLog(stringValue(result.get("snippet")), 180));
                item.put("source", result.get("source"));
                return item;
            })
            .toList();
    }

    private String truncateForLog(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }

    /**
     * Performs the first non blank operation.
     *
     * @param first the first value
     * @param second the second value
     * @return the operation result
     */
    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private Object firstObject(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            return value;
        }
        return null;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.split("[,\\n]")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
        }
        return List.of();
    }

    private record SearchAttempt(String provider, String endpoint) {
    }

    private record HtmlResponse(int statusCode, Document document) {
    }

    private record RawHttpResponse(int statusCode,
                                   String body,
                                   Map<String, String> cookies,
                                   String url) {
    }

    private record SiteSearchForm(String actionUrl,
                                  Map<String, String> parameters,
                                  String method,
                                  String submittedUrl,
                                  String sourcePageUrl) {
    }

    private record WebSearchRequestContext(String query,
                                           String tenantId,
                                           String userId,
                                           List<String> roles,
                                           String taskId,
                                           String agentId,
                                           String referer,
                                           List<String> allowedDomains,
                                           List<String> blockedDomains) {
    }

    private static class ProxyRuntimeState {

        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicInteger failureCount = new AtomicInteger();
        private volatile long openUntilMs;
    }
}
