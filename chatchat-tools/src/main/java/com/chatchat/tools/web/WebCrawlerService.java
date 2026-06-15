package com.chatchat.tools.web;

import com.chatchat.tools.playwright.PlaywrightBrowserSupport;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WebCrawlerService {

    private static final Logger log = LoggerFactory.getLogger(WebCrawlerService.class);

    private static final Pattern REQUESTED_URL_FIELD_PATTERN = Pattern.compile(
        "(?i)[\"']?requestedUrl[\"']?\\s*[:=]\\s*[\"'](https?://[^\"'\\s<>]+)[\"']");

    private static final Pattern ABSOLUTE_URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern SZSE_INDIVIDUAL_PAGE_PATTERN = Pattern.compile(
        "(?i)^https?://www\\.szse\\.cn/certificate/individual/index\\.html\\?[^#]*\\bcode=([0-9]{6})\\b.*$");

    private final WebCrawlerProperties properties;
    private final WebContentProcessor contentProcessor;
    private final WebToolCache cacheService;
    private final PlaywrightBrowserSupport playwrightSupport = new PlaywrightBrowserSupport("web_crawler");
    private final AtomicInteger proxyCursor = new AtomicInteger();

    public WebCrawlerService(WebCrawlerProperties properties,
                             WebContentProcessor contentProcessor,
                             WebToolCache cacheService) {
        this.properties = properties;
        this.contentProcessor = contentProcessor;
        this.cacheService = cacheService == null ? WebToolCache.NOOP : cacheService;
    }

    /**
     * Crawls a URL with cache-first behavior.
     *
     * @param url the url value
     * @param render the render value
     * @param timeoutMs the timeout ms value
     * @return the operation result
     */
    public Map<String, Object> crawl(String url, boolean render, int timeoutMs) {
        return crawl(url, render ? "browser" : properties.getDefaultMode(), render, timeoutMs, CrawlRequestContext.empty());
    }

    /**
     * Crawls a URL with a requested fetch mode.
     *
     * @param url the url value
     * @param mode java, browser, or auto
     * @param render the render value
     * @param timeoutMs the timeout ms value
     * @return the operation result
     */
    public Map<String, Object> crawl(String url, String mode, boolean render, int timeoutMs) {
        return crawl(url, mode, render, timeoutMs, CrawlRequestContext.empty());
    }

    /**
     * Crawls a URL with a requested fetch mode and proxy selection context.
     *
     * @param url the url value
     * @param mode java, browser, or auto
     * @param render the render value
     * @param timeoutMs the timeout ms value
     * @param context the crawl request context value
     * @return the operation result
     */
    public Map<String, Object> crawl(String url,
                                     String mode,
                                     boolean render,
                                     int timeoutMs,
                                     CrawlRequestContext context) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("web crawler is disabled");
        }
        String normalizedUrl = normalizeUrl(url);
        String crawlMode = resolveMode(mode, render);
        WebCrawlerProperties.ProxyConfig proxy = chooseProxy(context);
        String cacheNamespace = "page:" + crawlMode + ":" + proxyId(proxy);
        WebToolCache.CacheLookup cache = cacheService.get(cacheNamespace, normalizedUrl);
        if (cache.hit()) {
            Map<String, Object> cached = new LinkedHashMap<>(cache.data());
            cached.put("cacheHit", true);
            cached.put("cacheAgeSeconds", cache.ageSeconds());
            return cached;
        }

        long startedAt = System.currentTimeMillis();
        int timeout = timeoutMs > 0 ? timeoutMs : properties.getTimeoutMs();
        try {
            List<Map<String, Object>> crawlChain = new ArrayList<>();
            Set<String> visited = new LinkedHashSet<>();
            String currentUrl = normalizedUrl;
            Map<String, Object> output = null;
            int maxFollowUrls = Math.max(0, properties.getMaxFollowUrls());
            for (int hop = 0; hop <= maxFollowUrls; hop++) {
                String comparableUrl = normalizeComparableUrl(currentUrl);
                if (!visited.add(comparableUrl)) {
                    log.warn("Web crawler follow chain stopped because URL was already visited url={}", currentUrl);
                    break;
                }
                log.info("Web crawler request started url={} mode={} renderRequested={} timeoutMs={} proxy={} hop={}/{}",
                    currentUrl, crawlMode, render, timeout, proxyId(proxy), hop, maxFollowUrls);
                String effectiveCrawlMode = effectiveCrawlMode(crawlMode, currentUrl);
                CrawlResponse response = "browser".equals(effectiveCrawlMode)
                    ? fetchWithBrowser(currentUrl, timeout, proxy)
                    : fetchWithJava(currentUrl, timeout, proxy);
                output = buildOutput(normalizedUrl, response, effectiveCrawlMode, render, startedAt, proxy);
                output.put("requestedCrawlMode", crawlMode);
                output.put("browserFallbackUsed", !crawlMode.equals(effectiveCrawlMode));
                crawlChain.add(crawlChainItem(hop, currentUrl, response, output));
                String nextUrl = nextFollowUrl(output, response.html(), response.url());
                if (!isHttpUrl(nextUrl)) {
                    break;
                }
                String normalizedNextUrl = normalizeUrl(nextUrl);
                if (visited.contains(normalizeComparableUrl(normalizedNextUrl))) {
                    log.warn("Web crawler follow chain stopped before loop nextUrl={}", normalizedNextUrl);
                    break;
                }
                currentUrl = normalizedNextUrl;
            }
            if (output == null) {
                throw new IllegalStateException("No crawl response was produced");
            }
            output.put("crawlChain", crawlChain);
            output.put("followedUrlCount", Math.max(0, crawlChain.size() - 1));
            if (!crawlChain.isEmpty()) {
                output.put("finalRequestedUrl", crawlChain.get(crawlChain.size() - 1).get("requestedUrl"));
            }
            cacheService.put(cacheNamespace, normalizedUrl, output, properties.getCacheTtlSeconds());
            log.info("Web crawler request succeeded url={} mode={} engine={} proxy={} durationMs={} contentLength={}",
                output.get("url"), crawlMode, output.get("renderEngine"), proxyId(proxy), output.get("durationMs"), output.get("contentLength"));
            return output;
        } catch (Exception ex) {
            log.warn("Web crawler request failed url={} mode={} proxy={} error={}",
                normalizedUrl, crawlMode, proxyId(proxy), ex.getMessage());
            throw new IllegalStateException("Failed to crawl URL: " + ex.getMessage(), ex);
        }
    }

    private CrawlResponse fetchWithJava(String normalizedUrl,
                                        int timeout,
                                        WebCrawlerProperties.ProxyConfig proxyConfig) throws IOException {
        org.jsoup.Connection connection = Jsoup.connect(normalizedUrl)
            .userAgent(selectUserAgent())
            .header("Accept", browserAccept())
            .header("Accept-Language", browserAcceptLanguage())
            .timeout(timeout <= 0 ? 0 : Math.max(1000, timeout))
            .maxBodySize(Math.max(1024, properties.getMaxBodyBytes()))
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .followRedirects(true);
        applyBrowserLikeHeaders(connection, normalizedUrl);
        applyProxy(connection, proxyConfig);
        Document document = connection.get();
        String responseUrl = document.location() == null || document.location().isBlank()
            ? normalizedUrl
            : document.location();
        return new CrawlResponse(responseUrl, document.outerHtml(), false, "java_jsoup");
    }

    private CrawlResponse fetchWithBrowser(String normalizedUrl,
                                           int timeout,
                                           WebCrawlerProperties.ProxyConfig proxyConfig) {
        if (properties.getBrowser() == null || !properties.getBrowser().isEnabled()) {
            throw new IllegalStateException("browser crawl mode is disabled");
        }
        WebCrawlerProperties.BrowserProperties browserProperties = properties.getBrowser();
        int timeoutMs = timeout > 0 ? timeout : Math.max(0, browserProperties.getNavigationTimeoutMs());
        var launchOptions = playwrightSupport.headlessLaunchOptions(timeoutMs, playwrightProxyConfig(proxyConfig));
        try (Playwright playwright = playwrightSupport.createPlaywright(playwrightBrowserConfig(browserProperties));
             Browser browser = playwright.chromium().launch(launchOptions);
             BrowserContext browserContext = browser.newContext(playwrightContextOptions())) {
            Page page = browserContext.newPage();
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);
            page.navigate(normalizedUrl, new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            playwrightSupport.waitForNetworkIdle(page, timeoutMs,
                "Browser crawl page did not reach networkidle before content extraction");
            String html = page.content();
            if (html == null || html.isBlank()) {
                throw new IllegalStateException("Playwright returned empty page content");
            }
            return new CrawlResponse(page.url(), html, true, "playwright_chromium");
        }
    }

    private Map<String, Object> buildOutput(String requestedUrl,
                                            CrawlResponse response,
                                            String crawlMode,
                                            boolean render,
                                            long startedAt,
                                            WebCrawlerProperties.ProxyConfig proxyConfig) {
        WebContentProcessor.ProcessedContent content = contentProcessor.process(response.url(), response.html());
        String mainText = focusedContent(requestedUrl, response.url(), content.mainText());
        List<String> chunks = content.mainText().equals(mainText) ? content.chunks() : chunk(mainText);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("url", response.url());
        output.put("requestedUrl", requestedUrl);
        output.put("title", content.title());
        output.put("content", mainText);
        output.put("main_text", mainText);
        output.put("chunks", chunks);
        output.put("timestamp", Instant.now().toEpochMilli());
        output.put("crawlMode", crawlMode);
        output.put("renderRequested", render || "browser".equals(crawlMode));
        output.put("rendered", response.rendered());
        output.put("renderEngine", response.engine());
        output.put("cacheHit", false);
        output.put("contentLength", mainText.length());
        output.put("contentHash", cacheService.hash(mainText));
        output.put("durationMs", Math.max(0L, System.currentTimeMillis() - startedAt));
        output.put("html", properties.isIncludeHtml() ? truncate(response.html(), properties.getMaxHtmlChars()) : "");
        return output;
    }

    private String resolveMode(String mode, boolean render) {
        String requested = mode == null || mode.isBlank()
            ? properties.getDefaultMode()
            : mode.trim().toLowerCase(Locale.ROOT);
        if (render) {
            requested = "browser";
        }
        return switch (requested) {
            case "browser", "playwright", "render", "rendered" -> "browser";
            case "auto" -> properties.getBrowser() != null && properties.getBrowser().isEnabled() ? "browser" : "java";
            case "http", "jsoup", "java", "java_http" -> "java";
            default -> throw new IllegalArgumentException("unsupported crawl mode: " + requested);
        };
    }

    private String effectiveCrawlMode(String crawlMode, String url) {
        if ("java".equals(crawlMode) && requiresBrowserRendering(url)) {
            if (properties.getBrowser() != null && properties.getBrowser().isEnabled()) {
                return "browser";
            }
            log.warn("Web crawler URL appears to require browser rendering but browser mode is disabled url={}", url);
        }
        return crawlMode;
    }

    private boolean requiresBrowserRendering(String url) {
        return szseIndividualStockCode(url) != null;
    }

    private String focusedContent(String requestedUrl, String responseUrl, String mainText) {
        String value = mainText == null ? "" : mainText.trim();
        String stockCode = firstNonBlank(szseIndividualStockCode(responseUrl), szseIndividualStockCode(requestedUrl));
        if (stockCode == null || stockCode.isBlank()) {
            return value;
        }
        int start = value.indexOf(stockCode);
        if (start < 0) {
            return value;
        }
        int end = firstPositive(
            value.indexOf("返回顶部", start),
            value.indexOf("意见反馈", start),
            value.length()
        );
        String focused = value.substring(start, Math.min(value.length(), end)).trim();
        return focused.isBlank() ? value : focused;
    }

    private int firstPositive(int... values) {
        if (values == null || values.length == 0) {
            return -1;
        }
        int selected = -1;
        for (int value : values) {
            if (value >= 0 && (selected < 0 || value < selected)) {
                selected = value;
            }
        }
        return selected < 0 ? values[values.length - 1] : selected;
    }

    private String szseIndividualStockCode(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher matcher = SZSE_INDIVIDUAL_PAGE_PATTERN.matcher(url.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int chunkChars = Math.max(300, properties.getChunkChars());
        int overlap = Math.max(0, Math.min(properties.getChunkOverlapChars(), chunkChars / 3));
        int maxChunks = Math.max(1, properties.getMaxChunks());
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length() && chunks.size() < maxChunks) {
            int end = Math.min(text.length(), start + chunkChars);
            if (end < text.length()) {
                int sentenceEnd = Math.max(text.lastIndexOf('\u3002', end), text.lastIndexOf('.', end));
                if (sentenceEnd > start + chunkChars / 2) {
                    end = sentenceEnd + 1;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private PlaywrightBrowserSupport.BrowserConfig playwrightBrowserConfig(WebCrawlerProperties.BrowserProperties browserProperties) {
        return new PlaywrightBrowserSupport.BrowserConfig(
            browserProperties == null ? null : browserProperties.getBrowsersPath(),
            browserProperties != null && browserProperties.isSkipBrowserDownload()
        );
    }

    private PlaywrightBrowserSupport.ProxyConfig playwrightProxyConfig(WebCrawlerProperties.ProxyConfig proxyConfig) {
        return new PlaywrightBrowserSupport.ProxyConfig(
            proxyUsable(proxyConfig),
            proxyConfig == null ? null : proxyConfig.getType(),
            proxyConfig == null ? null : proxyConfig.getHost(),
            proxyConfig == null ? 0 : proxyConfig.getPort(),
            proxyConfig == null ? null : proxyConfig.getUsername(),
            proxyConfig == null ? null : proxyConfig.getPassword()
        );
    }

    private Browser.NewContextOptions playwrightContextOptions() {
        WebCrawlerProperties.BrowserProperties browser = properties.getBrowser();
        Map<String, String> headers = browserHeaders();
        return new Browser.NewContextOptions()
            .setUserAgent(selectUserAgent())
            .setLocale(browserLanguage(browserAcceptLanguage()))
            .setViewportSize(1365, 768)
            .setIgnoreHTTPSErrors(true)
            .setExtraHTTPHeaders(headers);
    }

    private void applyBrowserLikeHeaders(org.jsoup.Connection connection, String url) {
        Map<String, String> headers = browserHeaders();
        headers.forEach(connection::header);
        String referer = firstNonBlank(properties.getBrowser() == null ? null : properties.getBrowser().getReferer(), "");
        if (!referer.isBlank() && !sameOrigin(referer, url)) {
            connection.referrer(referer);
        }
    }

    private Map<String, String> browserHeaders() {
        WebCrawlerProperties.BrowserProperties browser = properties.getBrowser();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", browserAccept());
        headers.put("Accept-Language", browserAcceptLanguage());
        if (browser != null) {
            if (notBlank(browser.getReferer())) {
                headers.put("Referer", browser.getReferer());
            }
            putIfPresent(headers, "sec-ch-ua", browser.getSecChUa());
            putIfPresent(headers, "sec-ch-ua-mobile", browser.getSecChUaMobile());
            putIfPresent(headers, "sec-ch-ua-platform", browser.getSecChUaPlatform());
            if (browser.getHeaders() != null) {
                headers.putAll(browser.getHeaders());
            }
        }
        return headers;
    }

    private void applyProxy(org.jsoup.Connection connection, WebCrawlerProperties.ProxyConfig proxyConfig) {
        java.net.Proxy proxy = toProxy(proxyConfig);
        if (proxy != null) {
            connection.proxy(proxy);
        }
        if (proxyConfig != null && proxyConfig.getUsername() != null && !proxyConfig.getUsername().isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                (proxyConfig.getUsername() + ":" + firstNonBlank(proxyConfig.getPassword(), "")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            connection.header("Proxy-Authorization", "Basic " + token);
        }
    }

    private java.net.Proxy toProxy(WebCrawlerProperties.ProxyConfig config) {
        if (!proxyUsable(config)) {
            return null;
        }
        String type = firstNonBlank(config.getType(), "HTTP").trim().toUpperCase(Locale.ROOT);
        java.net.Proxy.Type proxyType = type.contains("SOCKS") ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP;
        return new java.net.Proxy(proxyType, new InetSocketAddress(config.getHost().trim(), config.getPort()));
    }

    private boolean proxyUsable(WebCrawlerProperties.ProxyConfig proxyConfig) {
        return properties.getProxyPool() != null
            && properties.getProxyPool().isEnabled()
            && proxyConfig != null
            && proxyConfig.getHost() != null
            && !proxyConfig.getHost().isBlank()
            && proxyConfig.getPort() > 0;
    }

    private WebCrawlerProperties.ProxyConfig chooseProxy(CrawlRequestContext context) {
        WebCrawlerProperties.ProxyPoolProperties proxyPool = properties.getProxyPool();
        if (proxyPool == null || !proxyPool.isEnabled() || proxyPool.getProxies() == null || proxyPool.getProxies().isEmpty()) {
            return null;
        }
        CrawlRequestContext requestContext = context == null ? CrawlRequestContext.empty() : context;
        List<WebCrawlerProperties.ProxyConfig> candidates = proxyPool.getProxies().stream()
            .filter(proxy -> matchesProxyScope(proxy, requestContext))
            .toList();
        if (candidates.isEmpty()) {
            candidates = proxyPool.getProxies();
        }
        int offset = Math.floorMod(proxyCursor.getAndIncrement(), candidates.size());
        return candidates.get(offset);
    }

    private boolean matchesProxyScope(WebCrawlerProperties.ProxyConfig proxy, CrawlRequestContext context) {
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

    private String proxyId(WebCrawlerProperties.ProxyConfig proxyConfig) {
        if (proxyConfig == null) {
            return "direct";
        }
        return firstNonBlank(proxyConfig.getId(), proxyConfig.getHost() + ":" + proxyConfig.getPort());
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

    private boolean sameOrigin(String first, String second) {
        try {
            URI firstUri = URI.create(first);
            URI secondUri = URI.create(second);
            return firstUri.getScheme().equalsIgnoreCase(secondUri.getScheme())
                && firstUri.getHost().equalsIgnoreCase(secondUri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (notBlank(value)) {
            target.put(key, value);
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private Map<String, Object> crawlChainItem(int hop,
                                               String requestedUrl,
                                               CrawlResponse response,
                                               Map<String, Object> output) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("hop", hop);
        item.put("requestedUrl", requestedUrl);
        item.put("url", response.url());
        item.put("title", output.get("title"));
        item.put("contentLength", output.get("contentLength"));
        item.put("rendered", response.rendered());
        item.put("renderEngine", response.engine());
        return item;
    }

    private String nextFollowUrl(Map<String, Object> output, String html, String baseUrl) {
        String fromRequestedUrlField = requestedUrlField(output, html);
        if (isHttpUrl(fromRequestedUrlField) && !sameUrl(fromRequestedUrlField, baseUrl)) {
            return fromRequestedUrlField;
        }
        String content = output == null ? "" : String.valueOf(output.getOrDefault("content", ""));
        String singleContentUrl = singleUrl(content);
        if (isHttpUrl(singleContentUrl) && !sameUrl(singleContentUrl, baseUrl)) {
            return singleContentUrl;
        }
        return null;
    }

    private String requestedUrlField(Map<String, Object> output, String html) {
        String content = output == null ? "" : String.valueOf(output.getOrDefault("content", ""));
        String value = requestedUrlField(content);
        if (isHttpUrl(value)) {
            return value;
        }
        return requestedUrlField(html);
    }

    private String requestedUrlField(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = REQUESTED_URL_FIELD_PATTERN.matcher(text);
        return matcher.find() ? trimTrailingUrlPunctuation(matcher.group(1)) : null;
    }

    private String singleUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = ABSOLUTE_URL_PATTERN.matcher(text.trim());
        if (!matcher.find()) {
            return null;
        }
        String first = trimTrailingUrlPunctuation(matcher.group());
        if (matcher.find()) {
            return null;
        }
        String withoutUrl = text.replace(first, "").replaceAll("[\\s\\p{Punct}\\u3000-\\u303f]+", "");
        return withoutUrl.isBlank() ? first : null;
    }

    private String trimTrailingUrlPunctuation(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("[\\]\\[)('\"，。；;、]+$", "");
    }

    private boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null
                && !uri.getHost().isBlank();
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean sameUrl(String first, String second) {
        if (!isHttpUrl(first) || !isHttpUrl(second)) {
            return false;
        }
        return normalizeComparableUrl(first).equals(normalizeComparableUrl(second));
    }

    private String normalizeComparableUrl(String url) {
        URI uri = URI.create(url.trim()).normalize();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String portPart = port < 0 ? "" : ":" + port;
        return scheme + "://" + host + portPart + path + query;
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

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int limit = Math.max(0, maxChars);
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record CrawlResponse(String url, String html, boolean rendered, String engine) {
    }

    public record CrawlRequestContext(String tenantId, String taskId, String agentId) {

        public static CrawlRequestContext empty() {
            return new CrawlRequestContext(null, null, null);
        }
    }
}
