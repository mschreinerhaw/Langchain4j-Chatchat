package com.chatchat.tools.builtin;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
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

    private final WebSearchToolProperties properties;
    private final ObjectMapper objectMapper;
    private final Semaphore rateSemaphore;
    private final Object rateLock = new Object();
    private final Object cookieLock = new Object();
    private final Map<String, Map<String, String>> cookieJar = new HashMap<>();
    private final Map<String, ProxyRuntimeState> proxyStates = new HashMap<>();
    private final AtomicInteger proxyCursor = new AtomicInteger();
    private final AtomicInteger dailyCalls = new AtomicInteger();
    private volatile LocalDate dailyWindow = LocalDate.now();
    private volatile long lastRequestAtMs;
    private volatile boolean playwrightBrowsersPathInfoLogged;
    private volatile boolean playwrightBrowsersPathWarningLogged;
    private volatile boolean playwrightSkipDownloadInfoLogged;
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

            Map<String, Object> result = performWebSearch(input, query, numResults);
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
    private Map<String, Object> performWebSearch(ToolInput input, String query, int numResults) throws Exception {
        String provider = properties.getProvider() == null
            ? "duckduckgo_html"
            : properties.getProvider().trim().toLowerCase(Locale.ROOT);

        WebSearchQueryIntent queryIntent = analyzeSearchQuery(query);
        WebSearchRequestContext context = requestContext(input, query);
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
                    properties.isFetchPages(),
                    properties.getMaxPagesToFetch());
                Map<String, Object> result = performWebSearchAttempt(
                    attempt.provider(), attempt.endpoint(), queryIntent, numResults, errors, context, networkAudit);
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
                                                        List<Map<String, Object>> networkAudit) throws Exception {
        String query = queryIntent.originalQuery();
        String searchQuery = queryIntent.searchQuery();
        String siteQuery = queryIntent.siteSearchQuery();
        HtmlResponse searchResponse = sendSearchEngineRequest(
            provider,
            endpoint,
            searchQuery,
            context,
            networkAudit
        );
        Document document = searchResponse.document();

        int referenceLimit = Math.max(0, Math.min(10, properties.getMaxResults()));
        int fetchLimit = Math.max(numResults, referenceLimit);

        List<Map<String, Object>> fetchedResults = switch (provider) {
            case "duckduckgo_html" -> parseDuckDuckGoResults(document, fetchLimit);
            case "bing_html" -> parseBingResults(document, fetchLimit);
            default -> throw new IllegalArgumentException("Unsupported web search provider: " + properties.getProvider());
        };
        List<Map<String, Object>> primaryResults = targetedPrimaryResults(fetchedResults, queryIntent);
        SearchResultRelevance searchResultRelevance = assessSearchResultRelevance(primaryResults, searchQuery);
        List<Map<String, Object>> siteSearchResults = runTargetedKnownSiteSearch(
            queryIntent,
            siteQuery,
            primaryResults,
            fetchLimit,
            context,
            networkAudit
        );
        List<Map<String, Object>> discoveredSiteSearchResults = discoverSiteSearchResults(
            primaryResults,
            siteQuery,
            fetchLimit,
            context,
            networkAudit,
            !searchResultRelevance.useful()
        );
        siteSearchResults = mergeSearchResults(siteSearchResults, discoveredSiteSearchResults, fetchLimit);
        List<Map<String, Object>> mergedResults = mergeSearchResults(primaryResults, siteSearchResults, fetchLimit);
        List<Map<String, Object>> results = mergedResults.size() <= numResults
            ? mergedResults
            : new ArrayList<>(mergedResults.subList(0, numResults));
        List<String> referenceUrls = mergedResults.stream()
            .map(item -> item.get("url"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(url -> !url.isBlank())
            .distinct()
            .limit(referenceLimit)
            .toList();
        List<Map<String, Object>> pageExcerpts = fetchPageExcerpts(results, query, context, networkAudit);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);
        output.put("search_query", searchQuery);
        output.put("site_search_query", siteQuery);
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
        output.put("site_search_enabled", properties.getSiteSearch().isEnabled());
        output.put("site_search_result_count", siteSearchResults.size());
        output.put("page_fetch_enabled", properties.isFetchPages());
        output.put("page_excerpt_count", pageExcerpts.size());
        output.put("contentMode", contentMode(pageExcerpts, siteSearchResults));
        output.put("structured_text", structuredSearchText(results, pageExcerpts, searchResultRelevance));
        output.put("web_search_audit", properties.getAudit().isIncludeInResult() ? networkAudit : List.of());
        output.put("pageExcerpts", pageExcerpts);
        output.put("evidenceSnippets", pageExcerpts);
        output.put("results", results);
        return output;
    }

    private WebSearchQueryIntent analyzeSearchQuery(String query) {
        String original = query == null ? "" : query.trim();
        String targetUrl = firstUrl(original);
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

    private List<Map<String, Object>> targetedPrimaryResults(List<Map<String, Object>> fetchedResults,
                                                             WebSearchQueryIntent queryIntent) {
        if (queryIntent.targetHost() == null || queryIntent.targetHost().isBlank()) {
            return fetchedResults;
        }
        List<Map<String, Object>> targeted = new ArrayList<>();
        if (fetchedResults != null) {
            for (Map<String, Object> result : fetchedResults) {
                String url = stringValue(result.get("url"));
                if (sameSearchDomain(url, queryIntent.targetHost())) {
                    targeted.add(new LinkedHashMap<>(result));
                }
            }
        }
        return targeted;
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

    private SearchResultRelevance assessSearchResultRelevance(List<Map<String, Object>> results, String query) {
        List<String> terms = queryTerms(query);
        if (results == null || results.isEmpty() || terms.isEmpty()) {
            return new SearchResultRelevance(false, results == null ? 0 : results.size(), 0, terms);
        }
        int matched = 0;
        for (Map<String, Object> result : results) {
            String text = String.join(" ",
                firstNonBlank(stringValue(result.get("title")), ""),
                firstNonBlank(stringValue(result.get("snippet")), ""),
                firstNonBlank(stringValue(result.get("url")), "")
            ).toLowerCase(Locale.ROOT);
            boolean resultMatched = false;
            for (String term : terms) {
                if (text.contains(term.toLowerCase(Locale.ROOT))) {
                    resultMatched = true;
                    break;
                }
            }
            result.put("keywordMatched", resultMatched);
            if (resultMatched) {
                matched++;
            }
        }
        return new SearchResultRelevance(matched > 0, results.size(), matched, terms);
    }

    private String structuredSearchText(List<Map<String, Object>> results,
                                        List<Map<String, Object>> pageExcerpts,
                                        SearchResultRelevance relevance) {
        StringBuilder builder = new StringBuilder();
        builder.append("search_result_useful: ").append(relevance.useful()).append('\n');
        builder.append("matched_results: ").append(relevance.matchedResults())
            .append('/').append(relevance.totalResults()).append('\n');
        builder.append("keywords: ").append(String.join(", ", relevance.keywords())).append('\n');
        builder.append("results:\n");
        if (results != null) {
            for (Map<String, Object> result : results) {
                builder.append("- rank: ").append(result.get("rank")).append('\n');
                builder.append("  title: ").append(firstNonBlank(stringValue(result.get("title")), "")).append('\n');
                builder.append("  url: ").append(firstNonBlank(stringValue(result.get("url")), "")).append('\n');
                builder.append("  source: ").append(firstNonBlank(stringValue(result.get("source")), "search_result")).append('\n');
                builder.append("  keywordMatched: ").append(Boolean.TRUE.equals(result.get("keywordMatched"))).append('\n');
                String snippet = firstNonBlank(stringValue(result.get("snippet")), "");
                if (!snippet.isBlank()) {
                    builder.append("  snippet: ").append(snippet.replaceAll("\\s+", " ").trim()).append('\n');
                }
                String pageExcerpt = firstNonBlank(stringValue(result.get("pageExcerpt")), "");
                if (!pageExcerpt.isBlank()) {
                    builder.append("  pageExcerpt: ").append(pageExcerpt.replaceAll("\\s+", " ").trim()).append('\n');
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

    private record WebSearchQueryIntent(
        String originalQuery,
        String searchQuery,
        String siteSearchQuery,
        String targetUrl,
        String targetHost
    ) {
    }

    private record NaturalTargetSite(String host, String url) {
    }

    private record SearchResultRelevance(boolean useful,
                                         int totalResults,
                                         int matchedResults,
                                         List<String> keywords) {

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("useful", useful);
            values.put("totalResults", totalResults);
            values.put("matchedResults", matchedResults);
            values.put("keywords", keywords);
            return values;
        }
    }

    private HtmlResponse sendSearchEngineRequest(String provider,
                                                 String endpoint,
                                                 String searchQuery,
                                                 WebSearchRequestContext context,
                                                 List<Map<String, Object>> networkAudit) throws Exception {
        if ("bing_html".equals(provider)) {
            return sendBingSearchRequest(endpoint, searchQuery, context, networkAudit);
        }
        return sendSearchPageRequest(
            endpoint,
            Map.of("q", searchQuery),
            searchQuery,
            "search",
            context,
            networkAudit
        );
    }

    private HtmlResponse sendBingSearchRequest(String endpoint,
                                               String searchQuery,
                                               WebSearchRequestContext context,
                                               List<Map<String, Object>> networkAudit) throws Exception {
        if (!hasQueryParameter(endpoint, "q") && isBingHost(endpoint)) {
            return sendHtmlRequest(
                urlWithRawQueryParams(endpoint, Map.of("q", searchQuery)),
                Map.of(),
                searchQuery,
                "search",
                context,
                networkAudit,
                "GET",
                false
            );
        }
        HtmlResponse landingResponse = sendSearchPageRequest(
            endpoint,
            Map.of(),
            searchQuery,
            "search_open",
            context,
            networkAudit
        );
        SiteSearchForm searchForm = firstSearchForm(landingResponse.document(), endpoint, searchQuery);
        if (searchForm == null) {
            if (hasQueryParameter(endpoint, "q") && looksLikeBingResultPage(landingResponse.document())) {
                log.info("Bing endpoint already returned a search result page endpoint={}", endpoint);
                return landingResponse;
            }
            log.warn("Bing search form was not found after opening endpoint={}, falling back to q parameter", endpoint);
            return sendSearchPageRequest(
                urlWithRawQueryParams(endpoint, Map.of("q", searchQuery)),
                Map.of(),
                searchQuery,
                "search",
                context,
                networkAudit
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
            return sendSearchPageRequest(
                submittedUrl,
                Map.of(),
                searchQuery,
                "search_submit",
                contextWithReferer(context, endpoint, searchQuery),
                networkAudit
            );
        }
        return sendSearchPageRequest(
            searchForm.actionUrl(),
            searchForm.parameters(),
            searchQuery,
            "search_submit",
            contextWithReferer(context, endpoint, searchQuery),
            networkAudit,
            searchForm.method()
        );
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
        if (!properties.isFetchPages() || results == null || results.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(0, Math.min(properties.getMaxPagesToFetch(), results.size()));
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
                result.put("pageFetched", false);
                result.put("pageFetchError", "unsupported url");
                continue;
            }
            if (!isAllowedUrl(url)) {
                result.put("pageFetched", false);
                result.put("pageFetchError", "domain not allowed");
                addAudit(networkAudit, query, url, "page", null, 0, 0, "BLOCKED", "domain not allowed");
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
                    result.put("pageFetched", true);
                    result.put("pageContentAvailable", false);
                    continue;
                }
                String excerpt = buildTextExcerpt(pageText, query, properties.getPageExcerptChars());
                result.put("pageFetched", true);
                result.put("pageContentAvailable", true);
                result.put("pageContentLength", pageText.length());
                result.put("pageContentTruncated", pageText.length() > excerpt.length());
                result.put("pageExcerpt", excerpt);

                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("rank", result.get("rank"));
                evidence.put("title", firstNonBlank(stringValue(result.get("title")), page.title()));
                evidence.put("url", url);
                evidence.put("excerpt", excerpt);
                excerpts.add(evidence);
                log.info("Web search page fetch succeeded attempt={}/{} rank={} statusCode={} durationMs={} excerptChars={}",
                    attempts,
                    limit,
                    result.get("rank"),
                    statusCode,
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    excerpt.length());
            } catch (Exception ex) {
                result.put("pageFetched", false);
                result.put("pageFetchError", ex.getMessage());
                log.warn("Web search page fetch failed attempt={}/{} rank={} url={} error={}",
                    attempts,
                    limit,
                    result.get("rank"),
                    url,
                    ex.getMessage());
            }
        }
        return excerpts;
    }

    private List<Map<String, Object>> discoverSiteSearchResults(List<Map<String, Object>> results,
                                                                String query,
                                                                int resultLimit,
                                                                WebSearchRequestContext context,
                                                                List<Map<String, Object>> networkAudit,
                                                                boolean inspectTopFive) {
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
            if (inspected >= inspectLimit || secondaryRequests >= secondaryLimit) {
                break;
            }
            String pageUrl = stringValue(result.get("url"));
            if (!isFetchablePageUrl(pageUrl) || !isAllowedUrl(pageUrl)) {
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
                        networkAudit
                    );
                    if (!knownResults.isEmpty()) {
                        result.put("siteSearchAvailable", true);
                        result.put("siteSearchType", "known_jsonp");
                        result.put("siteSearchResultCount", knownResults.size());
                        discovered.addAll(knownResults);
                        log.info("Web search site-search known endpoint succeeded url={} resultCount={} totalDiscovered={}",
                            pageUrl,
                            knownResults.size(),
                            discovered.size());
                        continue;
                    }
                }
                HtmlResponse pageResponse = sendSearchPageRequest(
                    pageUrl,
                    Map.of(),
                    query,
                    "site_search_page",
                    context,
                    networkAudit
                );
                boolean pageHasEvidence = pageContainsQueryEvidence(pageResponse.document(), query);
                result.put("siteSearchPageContainsQuery", pageHasEvidence);
                log.info("Web search site-search page inspected url={} queryEvidence={}", pageUrl, pageHasEvidence);
                if (secondaryRequests < secondaryLimit && knownSearchEndpoint(pageResponse.document(), pageUrl) != null) {
                    secondaryRequests++;
                    List<Map<String, Object>> knownResults = runKnownSiteSearch(
                        pageResponse.document(),
                        pageUrl,
                        query,
                        seenUrls,
                        Math.max(1, siteSearch.getMaxLinksPerPage()),
                        context,
                        networkAudit
                    );
                    if (!knownResults.isEmpty()) {
                        result.put("siteSearchAvailable", true);
                        result.put("siteSearchType", "known_jsonp");
                        result.put("siteSearchResultCount", knownResults.size());
                        discovered.addAll(knownResults);
                        log.info("Web search site-search known endpoint succeeded url={} resultCount={} totalDiscovered={}",
                            pageUrl,
                            knownResults.size(),
                            discovered.size());
                        continue;
                    }
                }
                List<SiteSearchForm> forms = findSiteSearchForms(pageResponse.document(), pageUrl, query);
                if (forms.isEmpty() && !pageHasEvidence) {
                    forms = discoverSearchFormsFromEntrypoints(
                        pageResponse.document(),
                        pageUrl,
                        query,
                        context,
                        networkAudit,
                        inspectTopFive ? 5 : Math.max(0, inspectLimit - inspected)
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
                        form.method()
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
                    log.info("Web search site-search submit completed sourceUrl={} resultCount={} totalDiscovered={}",
                        pageUrl,
                        secondaryResults.size(),
                        discovered.size());
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
                                                                 List<Map<String, Object>> networkAudit) {
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
                networkAudit
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
                                                                    int maxAdditionalInspections) {
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
            if (inspected >= maxAdditionalInspections || !isFetchablePageUrl(candidateUrl) || !isAllowedUrl(candidateUrl)) {
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
                    networkAudit
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

    private boolean pageContainsQueryEvidence(Document document, String query) {
        if (document == null || query == null || query.isBlank()) {
            return false;
        }
        String text = cleanHtmlText(document.title() + " " + document.body().text()).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return false;
        }
        int matched = 0;
        for (String term : terms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                matched++;
            }
        }
        return matched > 0 && (terms.size() <= 2 || matched >= Math.min(2, terms.size()));
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
                                                         List<Map<String, Object>> networkAudit) throws Exception {
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
        String submittedUrl = urlWithRawQueryParams(endpoint, parameters);
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
            networkAudit
        );
        String payload = response.document() == null ? "" : response.document().text();
        List<Map<String, Object>> results = parseKnownJsonpSearchResults(
            payload,
            sourcePageUrl,
            submittedUrl,
            query,
            seenUrls,
            maxLinks
        );
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
            context.taskId(),
            context.agentId(),
            context.proxyPool(),
            referer
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

    private HtmlResponse sendPlaywrightOnlyRequest(String url,
                                                   Map<String, String> queryParams,
                                                   String query,
                                                   String phase,
                                                   WebSearchRequestContext context,
                                                   List<Map<String, Object>> networkAudit,
                                                   String httpMethod) throws Exception {
        if (!isAllowedUrl(url)) {
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
        if (!isAllowedUrl(url)) {
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
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
            .setHeadless(true)
            .setTimeout(timeoutMs);
        String proxyArgument = browserProxyArgument(proxy);
        if (proxyArgument != null) {
            launchOptions.setProxy(new com.microsoft.playwright.options.Proxy(proxyArgument));
        }
        try (Playwright playwright = createPlaywright(browserProperties);
             Browser browser = playwright.chromium().launch(launchOptions);
             BrowserContext browserContext = browser.newContext(playwrightContextOptions(context))) {
            Page page = browserContext.newPage();
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);
            com.microsoft.playwright.Response response;
            if ("POST".equalsIgnoreCase(httpMethod)) {
                page.setContent(autoSubmitFormHtml(targetUrl, queryParams, "POST"));
                page.locator("#f").evaluate("form => form.submit()");
                waitForUsefulPlaywrightContent(page, timeoutMs);
                response = null;
            } else {
                response = page.navigate(targetUrl, new Page.NavigateOptions()
                    .setTimeout(timeoutMs)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                waitForUsefulPlaywrightContent(page, timeoutMs);
            }
            String html = page.content();
            if (html == null || html.isBlank()) {
                throw new IllegalStateException("Playwright returned empty page content");
            }
            return new HtmlResponse(response == null ? 200 : response.status(), Jsoup.parse(html, page.url()));
        }
    }

    private void waitForUsefulPlaywrightContent(Page page, int timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(Math.min(3000, Math.max(1000, timeoutMs / 3))));
        } catch (RuntimeException ex) {
            log.debug("Playwright page did not reach networkidle before content extraction: {}", ex.getMessage());
        }
    }

    private Playwright createPlaywright(WebSearchToolProperties.BrowserProperties browserProperties) {
        return Playwright.create(new Playwright.CreateOptions().setEnv(playwrightEnvironment(browserProperties)));
    }

    private Map<String, String> playwrightEnvironment(WebSearchToolProperties.BrowserProperties browserProperties) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String configuredBrowsersPath = browserProperties == null ? null : browserProperties.getBrowsersPath();
        String browsersPath = firstNonBlank(configuredBrowsersPath, env.get("PLAYWRIGHT_BROWSERS_PATH"));
        if (browsersPath != null && !browsersPath.isBlank()) {
            String normalizedPath = normalizePlaywrightBrowsersPath(browsersPath);
            env.put("PLAYWRIGHT_BROWSERS_PATH", normalizedPath);
            logPlaywrightBrowsersPath(normalizedPath);
            if (containsPlaywrightChromiumInstall(Path.of(normalizedPath))) {
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                logPlaywrightSkipDownload(normalizedPath);
            }
        }
        if (browserProperties != null && browserProperties.isSkipBrowserDownload()) {
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        }
        return env;
    }

    private String normalizePlaywrightBrowsersPath(String browsersPath) {
        String value = browsersPath == null ? "" : browsersPath.trim();
        if (value.isBlank() || "0".equals(value)) {
            return value;
        }
        Path path = resolvePlaywrightBrowsersPath(value);
        if (containsPlaywrightChromiumInstall(path)) {
            return path.toString();
        }
        String platformDirectory = playwrightPlatformDirectoryName();
        if (platformDirectory != null) {
            Path platformPath = path.resolve(platformDirectory);
            if (Files.isDirectory(platformPath)) {
                return platformPath.toString();
            }
        }
        return path.toString();
    }

    private Path resolvePlaywrightBrowsersPath(String browsersPath) {
        Path configuredPath = Path.of(browsersPath);
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path directPath = cwd.resolve(configuredPath).normalize();
        if (Files.exists(directPath)) {
            return directPath;
        }
        for (Path parent = cwd.getParent(); parent != null; parent = parent.getParent()) {
            Path candidate = parent.resolve(configuredPath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return directPath;
    }

    private boolean containsPlaywrightChromiumInstall(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (java.util.stream.Stream<Path> children = Files.list(path)) {
            return children
                .map(child -> child.getFileName() == null ? "" : child.getFileName().toString())
                .anyMatch(name -> name.startsWith("chromium-")
                    || name.startsWith("chromium_headless_shell-"));
        } catch (IOException ex) {
            return false;
        }
    }

    private String playwrightPlatformDirectoryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        return null;
    }

    private void logPlaywrightBrowsersPath(String browsersPath) {
        if (!playwrightBrowsersPathInfoLogged) {
            synchronized (this) {
                if (!playwrightBrowsersPathInfoLogged) {
                    log.info("Playwright browser cache path: {}", browsersPath);
                    playwrightBrowsersPathInfoLogged = true;
                }
            }
        }
        if (!"0".equals(browsersPath) && !Files.isDirectory(Path.of(browsersPath)) && !playwrightBrowsersPathWarningLogged) {
            synchronized (this) {
                if (!playwrightBrowsersPathWarningLogged) {
                    log.warn("Configured Playwright browser cache path does not exist yet: {}. "
                        + "Pre-download Chromium into this directory before running offline.", browsersPath);
                    playwrightBrowsersPathWarningLogged = true;
                }
            }
        }
    }

    private void logPlaywrightSkipDownload(String browsersPath) {
        if (!playwrightSkipDownloadInfoLogged) {
            synchronized (this) {
                if (!playwrightSkipDownloadInfoLogged) {
                    log.info("Playwright browser download skipped because cached Chromium was found: {}", browsersPath);
                    playwrightSkipDownloadInfoLogged = true;
                }
            }
        }
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

    private String browserProxyArgument(WebSearchToolProperties.ProxyConfig proxy) {
        if (!properties.getProxyPool().isEnabled()
            || proxy == null
            || proxy.getHost() == null
            || proxy.getHost().isBlank()
            || proxy.getPort() <= 0) {
            return null;
        }
        String type = firstNonBlank(proxy.getType(), "HTTP").toLowerCase(Locale.ROOT);
        String scheme = type.contains("socks") ? "socks5" : "http";
        return scheme + "://" + proxy.getHost().trim() + ":" + proxy.getPort();
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
            && phase.startsWith("site_search");
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
        return value == null ? "" : value.trim().replace(" ", "+");
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
        String pool = firstNonBlank(context.proxyPool(), properties.getProxyPool().getDefaultPool());
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
            firstNonBlank(stringValue(parameters.get("sourceTaskId")), stringValue(parameters.get("taskId"))),
            stringValue(parameters.get("agentId")),
            stringValue(parameters.get("proxyPool")),
            stringValue(parameters.get("referer"))
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

    private boolean isAllowedUrl(String url) {
        if (!properties.getAllowList().isEnabled()) {
            return true;
        }
        String host = hostOf(url);
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return properties.getAllowList().getDomains().stream()
            .filter(domain -> domain != null && !domain.isBlank())
            .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
            .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
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
        item.put("proxyId", firstNonBlank(proxyId, "direct"));
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
                                           String taskId,
                                           String agentId,
                                           String proxyPool,
                                           String referer) {
    }

    private static class ProxyRuntimeState {

        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicInteger failureCount = new AtomicInteger();
        private volatile long openUntilMs;
    }
}

