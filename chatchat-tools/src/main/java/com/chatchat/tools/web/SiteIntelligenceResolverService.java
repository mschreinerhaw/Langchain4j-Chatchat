package com.chatchat.tools.web;

import com.chatchat.tools.playwright.PlaywrightBrowserSupport;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SiteIntelligenceResolverService {

    private static final Pattern SEARCH_WORD_PATTERN = Pattern.compile(
        "(?i)(search|query|keyword|keywords|q=|wd=|word=|sousuo|so\\b|suggest|autocomplete|检索|搜索|查询)");

    private final WebCrawlerProperties properties;
    private final PlaywrightBrowserSupport playwrightSupport = new PlaywrightBrowserSupport("site_intelligence");

    public SiteIntelligenceResolverService(WebCrawlerProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> resolve(String url, String mode, String probeQuery, int timeoutMs) {
        String normalizedUrl = normalizeUrl(url);
        String resolvedMode = resolveMode(mode);
        int timeout = timeoutMs < 0 ? properties.getTimeoutMs() : timeoutMs;
        SiteDiscovery discovery = new SiteDiscovery(normalizedUrl, firstNonBlank(probeQuery, "test"));
        inspectStatic(normalizedUrl, timeout, discovery);
        if ("browser".equals(resolvedMode) || ("auto".equals(resolvedMode) && browserEnabled())) {
            inspectBrowser(normalizedUrl, timeout, discovery);
        }
        addHeuristicCandidates(normalizedUrl, discovery);
        return discovery.toMap(resolvedMode);
    }

    private void inspectStatic(String url, int timeoutMs, SiteDiscovery discovery) {
        try {
            Document document = Jsoup.connect(url)
                .userAgent(selectUserAgent())
                .header("Accept", browserAccept())
                .header("Accept-Language", browserAcceptLanguage())
                .timeout(timeoutMs <= 0 ? 0 : Math.max(1000, timeoutMs))
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .followRedirects(true)
                .get();
            discovery.title = firstNonBlank(document.title(), discovery.title);
            discovery.staticInspected = true;
            discoverForms(document, url, discovery, "static_form");
            discoverSearchLinks(document, url, discovery, "static_link");
            discoverSitemaps(document, url, discovery);
            discoverScripts(document, url, discovery);
        } catch (Exception ex) {
            discovery.errors.add("static_inspect_failed: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void inspectBrowser(String url, int timeoutMs, SiteDiscovery discovery) {
        if (!browserEnabled()) {
            discovery.errors.add("browser_disabled");
            return;
        }
        WebCrawlerProperties.BrowserProperties browserProperties = properties.getBrowser();
        int timeout = timeoutMs < 0 ? Math.max(0, browserProperties.getNavigationTimeoutMs()) : timeoutMs;
        Set<String> networkUrls = new LinkedHashSet<>();
        try (Playwright playwright = playwrightSupport.createPlaywright(playwrightBrowserConfig(browserProperties));
             Browser browser = playwright.chromium().launch(playwrightSupport.headlessLaunchOptions(timeout, null));
             BrowserContext context = browser.newContext(playwrightContextOptions())) {
            Page page = context.newPage();
            page.onRequest(request -> {
                String requestUrl = request.url();
                if (looksLikeSearchUrl(requestUrl)) {
                    networkUrls.add(requestUrl);
                }
            });
            page.setDefaultTimeout(timeout);
            page.setDefaultNavigationTimeout(timeout);
            page.navigate(url, new Page.NavigateOptions()
                .setTimeout(timeout)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            playwrightSupport.waitForNetworkIdle(page, timeout,
                "Site intelligence page did not reach networkidle before capability extraction");
            discovery.browserInspected = true;
            discovery.finalUrl = firstNonBlank(page.url(), discovery.finalUrl);
            Object rawInputs = page.evaluate("""
                () => Array.from(document.querySelectorAll('input,textarea,[contenteditable=true]')).slice(0, 30).map((el) => ({
                  tag: el.tagName.toLowerCase(),
                  type: el.getAttribute('type') || '',
                  name: el.getAttribute('name') || '',
                  id: el.getAttribute('id') || '',
                  cls: el.getAttribute('class') || '',
                  placeholder: el.getAttribute('placeholder') || '',
                  aria: el.getAttribute('aria-label') || '',
                  visible: !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)
                }))
                """);
            if (rawInputs instanceof List<?> inputs) {
                for (Object input : inputs) {
                    if (input instanceof Map<?, ?> map && looksLikeSearchInput(map)) {
                        discovery.hasSearchBox = true;
                        discovery.searchBoxCandidates.add(stringMap(map));
                    }
                }
            }
            for (String requestUrl : networkUrls) {
                discovery.addEndpoint(requestUrl, "browser_network", 0.78);
            }
            Document rendered = Jsoup.parse(page.content(), page.url());
            discoverForms(rendered, page.url(), discovery, "browser_form");
            discoverSearchLinks(rendered, page.url(), discovery, "browser_link");
        } catch (Exception ex) {
            discovery.errors.add("browser_inspect_failed: " + ex.getMessage());
        }
    }

    private void discoverForms(Document document, String baseUrl, SiteDiscovery discovery, String source) {
        for (Element form : document.select("form")) {
            Element input = bestSearchInput(form);
            if (input == null && !looksLikeSearchText(form.text() + " " + form.attr("action") + " " + form.attr("class") + " " + form.attr("id"))) {
                continue;
            }
            discovery.hasSearchBox = true;
            String action = firstNonBlank(form.absUrl("action"), baseUrl);
            String method = firstNonBlank(form.attr("method"), "GET").toUpperCase(Locale.ROOT);
            String queryParam = input == null ? "q" : firstNonBlank(input.attr("name"), firstNonBlank(input.attr("id"), "q"));
            String template = "GET".equals(method)
                ? urlWithQueryPlaceholder(action, queryParam)
                : action;
            discovery.addEndpoint(template, source, 0.86);
            discovery.discoveredRoutes.add(route("internal_search_form", action, 0.82, source));
        }
    }

    private void discoverSearchLinks(Document document, String baseUrl, SiteDiscovery discovery, String source) {
        for (Element link : document.select("a[href]")) {
            String href = firstNonBlank(link.absUrl("href"), link.attr("href"));
            if (href == null || href.isBlank() || !sameSite(baseUrl, href)) {
                continue;
            }
            String text = link.text() + " " + href;
            if (!looksLikeSearchText(text)) {
                continue;
            }
            double confidence = href.toLowerCase(Locale.ROOT).contains("search") ? 0.78 : 0.62;
            discovery.addEndpoint(templateFromSearchUrl(href), source, confidence);
            discovery.discoveredRoutes.add(route("internal_search_page", href, confidence, source));
        }
    }

    private void discoverSitemaps(Document document, String baseUrl, SiteDiscovery discovery) {
        for (Element link : document.select("link[href], a[href]")) {
            String href = firstNonBlank(link.absUrl("href"), link.attr("href"));
            if (href != null && href.toLowerCase(Locale.ROOT).contains("sitemap")) {
                discovery.hasSitemap = true;
                discovery.sitemapUrls.add(href);
            }
        }
        String origin = originOf(baseUrl);
        if (origin != null) {
            discovery.hasSitemap = true;
            discovery.sitemapUrls.add(origin + "/sitemap.xml");
        }
    }

    private void discoverScripts(Document document, String baseUrl, SiteDiscovery discovery) {
        for (Element script : document.select("script[src]")) {
            String src = firstNonBlank(script.absUrl("src"), script.attr("src"));
            if (looksLikeSearchUrl(src)) {
                discovery.addEndpoint(src, "static_script", 0.55);
            }
        }
    }

    private void addHeuristicCandidates(String url, SiteDiscovery discovery) {
        String origin = originOf(url);
        String host = hostOf(url);
        if (origin == null || host == null) {
            return;
        }
        for (String path : List.of("/search", "/search/", "/search.html", "/sousuo", "/sousuo/", "/so", "/so/")) {
            discovery.addEndpoint(origin + path + "?q={q}", "heuristic_path", 0.42);
            discovery.discoveredRoutes.add(route("heuristic_search_page", origin + path, 0.42, "heuristic_path"));
        }
        if (host.endsWith("sina.com.cn")) {
            discovery.addEndpoint("https://search.sina.com.cn/?q={q}&c=news&from=finance", "known_site_rule", 0.82);
            discovery.addEndpoint("https://search.sina.com.cn/?q={q}", "known_site_rule", 0.74);
            discovery.discoveredRoutes.add(route("cross_site_search_page", "https://search.sina.com.cn/", 0.82, "known_site_rule"));
        }
        if (host.endsWith("eastmoney.com")) {
            discovery.addEndpoint("https://so.eastmoney.com/web/s?keyword={q}", "known_site_rule", 0.82);
        }
        if (host.endsWith("10jqka.com.cn")) {
            discovery.addEndpoint("https://search.10jqka.com.cn/search?w={q}", "known_site_rule", 0.78);
        }
        if (host.endsWith("jiqizhixin.com")) {
            discovery.addEndpoint("https://www.jiqizhixin.com/articles?keyword={q}", "known_site_rule", 0.72);
            discovery.addEndpoint("https://www.jiqizhixin.com/articles?query={q}", "known_site_rule", 0.68);
            discovery.discoveredRoutes.add(route("article_library", "https://www.jiqizhixin.com/articles", 0.78, "known_site_rule"));
        }
    }

    private Element bestSearchInput(Element scope) {
        Element best = null;
        int bestScore = 0;
        for (Element input : scope.select("input,textarea")) {
            String type = firstNonBlank(input.attr("type"), "text").toLowerCase(Locale.ROOT);
            if (Set.of("hidden", "submit", "button", "reset", "checkbox", "radio", "file", "image").contains(type)) {
                continue;
            }
            String text = String.join(" ",
                type,
                input.attr("name"),
                input.attr("id"),
                input.attr("class"),
                input.attr("placeholder"),
                input.attr("aria-label")
            );
            int score = searchTextScore(text);
            if ("search".equals(type)) {
                score += 8;
            }
            if (score > bestScore) {
                best = input;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private boolean looksLikeSearchInput(Map<?, ?> input) {
        if (Boolean.FALSE.equals(input.get("visible"))) {
            return false;
        }
        String text = String.join(" ",
            stringValue(input.get("type")),
            stringValue(input.get("name")),
            stringValue(input.get("id")),
            stringValue(input.get("cls")),
            stringValue(input.get("placeholder")),
            stringValue(input.get("aria"))
        );
        return searchTextScore(text) > 0;
    }

    private int searchTextScore(String text) {
        String value = firstNonBlank(text, "").toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : List.of("search", "query", "keyword", "keywords", "q", "wd", "word", "sousuo", "suggest", "autocomplete")) {
            if (value.contains(term)) {
                score += 3;
            }
        }
        for (String term : List.of("搜索", "检索", "查询", "关键词")) {
            if (value.contains(term)) {
                score += 5;
            }
        }
        return score;
    }

    private boolean looksLikeSearchText(String text) {
        return text != null && SEARCH_WORD_PATTERN.matcher(text).find();
    }

    private boolean looksLikeSearchUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return looksLikeSearchText(url) || url.toLowerCase(Locale.ROOT).contains("/api/");
    }

    private String urlWithQueryPlaceholder(String action, String queryParam) {
        String param = firstNonBlank(queryParam, "q");
        if (action == null || action.isBlank()) {
            return "?"+ param + "={q}";
        }
        if (action.contains("{q}")) {
            return action;
        }
        String separator = action.contains("?") ? "&" : "?";
        return action + separator + param + "={q}";
    }

    private String templateFromSearchUrl(String url) {
        if (url == null || url.isBlank() || url.contains("{q}")) {
            return url;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String param : List.of("q=", "query=", "keyword=", "keywords=", "wd=", "word=", "w=")) {
            int index = lower.indexOf(param);
            if (index >= 0) {
                int valueStart = index + param.length();
                int valueEnd = url.indexOf('&', valueStart);
                return valueEnd < 0
                    ? url.substring(0, valueStart) + "{q}"
                    : url.substring(0, valueStart) + "{q}" + url.substring(valueEnd);
            }
        }
        return urlWithQueryPlaceholder(url, "q");
    }

    private Map<String, Object> route(String type, String url, double confidence, String source) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("url", url);
        item.put("confidence", confidence);
        item.put("source", source);
        return item;
    }

    private Browser.NewContextOptions playwrightContextOptions() {
        WebCrawlerProperties.BrowserProperties browser = properties.getBrowser();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", browserAccept());
        headers.put("Accept-Language", browserAcceptLanguage());
        if (browser != null && browser.getHeaders() != null) {
            headers.putAll(browser.getHeaders());
        }
        return new Browser.NewContextOptions()
            .setUserAgent(selectUserAgent())
            .setLocale(browserLanguage(browserAcceptLanguage()))
            .setViewportSize(1365, 768)
            .setIgnoreHTTPSErrors(true)
            .setExtraHTTPHeaders(headers);
    }

    private PlaywrightBrowserSupport.BrowserConfig playwrightBrowserConfig(WebCrawlerProperties.BrowserProperties browserProperties) {
        return new PlaywrightBrowserSupport.BrowserConfig(
            browserProperties == null ? null : browserProperties.getBrowsersPath(),
            browserProperties != null && browserProperties.isSkipBrowserDownload()
        );
    }

    private String recommendedStrategy(SiteDiscovery discovery) {
        if (!discovery.endpointCandidates.isEmpty()) {
            boolean browser = discovery.browserInspected && discovery.endpointCandidates.stream()
                .anyMatch(item -> "browser_network".equals(item.get("source")) || "browser_form".equals(item.get("source")));
            return browser ? "browser_search_then_dom_extract" : "direct_search_endpoint_then_extract";
        }
        if (discovery.hasSitemap) {
            return "sitemap_or_category_crawl_then_rerank";
        }
        return discovery.browserInspected ? "browser_crawl_navigation_then_rerank" : "static_crawl_navigation_then_rerank";
    }

    private String searchType(SiteDiscovery discovery) {
        if (discovery.endpointCandidates.stream().anyMatch(item -> String.valueOf(item.get("url")).contains("/api/"))) {
            return "api_or_xhr";
        }
        if (discovery.hasSearchBox) {
            return "html_form";
        }
        if (!discovery.endpointCandidates.isEmpty()) {
            return "url_template";
        }
        return "none";
    }

    private String resolveMode(String mode) {
        String value = mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "java", "static", "http" -> "java";
            case "browser", "playwright", "render" -> "browser";
            default -> "auto";
        };
    }

    private boolean browserEnabled() {
        return properties.getBrowser() != null && properties.getBrowser().isEnabled();
    }

    private String selectUserAgent() {
        WebCrawlerProperties.BrowserProperties browser = properties.getBrowser();
        List<String> userAgents = browser == null ? List.of() : browser.getUserAgents();
        if (userAgents != null && !userAgents.isEmpty()) {
            return userAgents.get(Math.floorMod(System.nanoTime(), userAgents.size()));
        }
        return firstNonBlank(properties.getUserAgent(), "ChatChat-MCP-Crawler/1.0");
    }

    private String browserAccept() {
        WebCrawlerProperties.BrowserProperties browser = properties.getBrowser();
        return firstNonBlank(browser == null ? null : browser.getAccept(), "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    }

    private String browserAcceptLanguage() {
        WebCrawlerProperties.BrowserProperties browser = properties.getBrowser();
        return firstNonBlank(browser == null ? null : browser.getAcceptLanguage(), properties.getAcceptLanguage());
    }

    private String browserLanguage(String acceptLanguage) {
        String value = firstNonBlank(acceptLanguage, "en-US");
        int separator = value.indexOf(',');
        return separator > 0 ? value.substring(0, separator).trim() : value.trim();
    }

    private boolean sameSite(String first, String second) {
        String firstHost = hostOf(first);
        String secondHost = hostOf(second);
        if (firstHost == null || secondHost == null) {
            return false;
        }
        return firstHost.equalsIgnoreCase(secondHost)
            || firstHost.endsWith("." + secondHost)
            || secondHost.endsWith("." + firstHost)
            || registrableishDomain(firstHost).equals(registrableishDomain(secondHost));
    }

    private String registrableishDomain(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String[] parts = host.toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length <= 2) {
            return host.toLowerCase(Locale.ROOT);
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private String hostOf(String url) {
        try {
            return URI.create(url.trim()).getHost();
        } catch (Exception ex) {
            return null;
        }
    }

    private String originOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = firstNonBlank(uri.getScheme(), "https");
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            return scheme + "://" + host;
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("only http/https URLs are supported");
        }
        return uri.normalize().toString();
    }

    private Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                values.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return values;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static class SiteDiscovery {

        private final String baseUrl;
        private final String probeQuery;
        private String finalUrl;
        private String title = "";
        private boolean staticInspected;
        private boolean browserInspected;
        private boolean hasSearchBox;
        private boolean hasSitemap;
        private final List<String> sitemapUrls = new ArrayList<>();
        private final List<Map<String, Object>> endpointCandidates = new ArrayList<>();
        private final List<Map<String, Object>> discoveredRoutes = new ArrayList<>();
        private final List<Map<String, Object>> searchBoxCandidates = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final Set<String> seenEndpoints = new LinkedHashSet<>();

        private SiteDiscovery(String baseUrl, String probeQuery) {
            this.baseUrl = baseUrl;
            this.finalUrl = baseUrl;
            this.probeQuery = probeQuery;
        }

        private void addEndpoint(String url, String source, double confidence) {
            if (url == null || url.isBlank()) {
                return;
            }
            String key = url.trim();
            if (!seenEndpoints.add(key)) {
                return;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", key);
            item.put("source", source);
            item.put("confidence", confidence);
            item.put("sample_url", key.replace("{q}", URLEncoder.encode(probeQuery, StandardCharsets.UTF_8)));
            endpointCandidates.add(item);
        }

        private Map<String, Object> toMap(String mode) {
            Map<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("has_search_box", hasSearchBox);
            capabilities.put("search_type", searchTypeStatic(this));
            capabilities.put("search_endpoint_candidates", endpointCandidates);
            capabilities.put("has_api", endpointCandidates.stream().anyMatch(item -> String.valueOf(item.get("url")).contains("/api/")));
            capabilities.put("has_sitemap", hasSitemap);
            capabilities.put("sitemap_urls", sitemapUrls.stream().distinct().toList());
            capabilities.put("search_box_candidates", searchBoxCandidates);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("base_url", baseUrl);
            result.put("final_url", finalUrl);
            result.put("title", title);
            result.put("mode", mode);
            result.put("static_inspected", staticInspected);
            result.put("browser_inspected", browserInspected);
            result.put("capabilities", capabilities);
            result.put("discovered_routes", discoveredRoutes);
            result.put("recommended_strategy", recommendedStrategyStatic(this));
            result.put("errors", errors);
            return result;
        }

        private static String searchTypeStatic(SiteDiscovery discovery) {
            if (discovery.endpointCandidates.stream().anyMatch(item -> String.valueOf(item.get("url")).contains("/api/"))) {
                return "api_or_xhr";
            }
            if (discovery.hasSearchBox) {
                return "html_form";
            }
            if (!discovery.endpointCandidates.isEmpty()) {
                return "url_template";
            }
            return "none";
        }

        private static String recommendedStrategyStatic(SiteDiscovery discovery) {
            if (!discovery.endpointCandidates.isEmpty()) {
                boolean browser = discovery.browserInspected && discovery.endpointCandidates.stream()
                    .anyMatch(item -> "browser_network".equals(item.get("source")) || "browser_form".equals(item.get("source")));
                return browser ? "browser_search_then_dom_extract" : "direct_search_endpoint_then_extract";
            }
            if (discovery.hasSitemap) {
                return "sitemap_or_category_crawl_then_rerank";
            }
            return discovery.browserInspected ? "browser_crawl_navigation_then_rerank" : "static_crawl_navigation_then_rerank";
        }
    }
}
