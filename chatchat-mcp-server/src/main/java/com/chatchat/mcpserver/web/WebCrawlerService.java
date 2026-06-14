package com.chatchat.mcpserver.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WebCrawlerService {

    private static final Logger log = LoggerFactory.getLogger(WebCrawlerService.class);

    private final WebCrawlerProperties properties;
    private final WebContentProcessor contentProcessor;
    private final WebPageCacheService cacheService;
    private final AtomicInteger proxyCursor = new AtomicInteger();
    private volatile boolean playwrightBrowsersPathInfoLogged;
    private volatile boolean playwrightBrowsersPathWarningLogged;
    private volatile boolean playwrightSkipDownloadInfoLogged;

    public WebCrawlerService(WebCrawlerProperties properties,
                             WebContentProcessor contentProcessor,
                             WebPageCacheService cacheService) {
        this.properties = properties;
        this.contentProcessor = contentProcessor;
        this.cacheService = cacheService;
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
        WebPageCacheService.CacheLookup cache = cacheService.get(cacheNamespace, normalizedUrl);
        if (cache.hit()) {
            Map<String, Object> cached = new LinkedHashMap<>(cache.data());
            cached.put("cacheHit", true);
            cached.put("cacheAgeSeconds", cache.ageSeconds());
            return cached;
        }

        long startedAt = System.currentTimeMillis();
        int timeout = timeoutMs > 0 ? timeoutMs : properties.getTimeoutMs();
        try {
            log.info("Web crawler request started url={} mode={} renderRequested={} timeoutMs={} proxy={}",
                normalizedUrl, crawlMode, render, timeout, proxyId(proxy));
            CrawlResponse response = "browser".equals(crawlMode)
                ? fetchWithBrowser(normalizedUrl, timeout, proxy)
                : fetchWithJava(normalizedUrl, timeout, proxy);
            Map<String, Object> output = buildOutput(normalizedUrl, response, crawlMode, render, startedAt, proxy);
            cacheService.put(cacheNamespace, normalizedUrl, output, properties.getCacheTtlSeconds());
            log.info("Web crawler request succeeded url={} mode={} engine={} proxy={} durationMs={} contentLength={}",
                response.url(), crawlMode, response.engine(), proxyId(proxy), output.get("durationMs"), output.get("contentLength"));
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
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
            .setHeadless(true)
            .setTimeout(timeoutMs);
        com.microsoft.playwright.options.Proxy playwrightProxy = playwrightProxy(proxyConfig);
        if (playwrightProxy != null) {
            launchOptions.setProxy(playwrightProxy);
        }
        try (Playwright playwright = createPlaywright(browserProperties);
             Browser browser = playwright.chromium().launch(launchOptions);
             BrowserContext browserContext = browser.newContext(playwrightContextOptions())) {
            Page page = browserContext.newPage();
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);
            page.navigate(normalizedUrl, new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            waitForUsefulBrowserContent(page, timeoutMs);
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
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("url", response.url());
        output.put("requestedUrl", requestedUrl);
        output.put("title", content.title());
        output.put("content", content.mainText());
        output.put("main_text", content.mainText());
        output.put("chunks", content.chunks());
        output.put("keywords", content.keywords());
        output.put("timestamp", Instant.now().toEpochMilli());
        output.put("crawlMode", crawlMode);
        output.put("renderRequested", render || "browser".equals(crawlMode));
        output.put("rendered", response.rendered());
        output.put("renderEngine", response.engine());
        output.put("cacheHit", false);
        output.put("contentLength", content.mainText().length());
        output.put("contentHash", cacheService.hash(content.mainText()));
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

    private void waitForUsefulBrowserContent(Page page, int timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(Math.min(3000, Math.max(1000, timeoutMs / 3))));
        } catch (RuntimeException ex) {
            log.debug("Browser crawl page did not reach networkidle before content extraction: {}", ex.getMessage());
        }
    }

    private Playwright createPlaywright(WebCrawlerProperties.BrowserProperties browserProperties) {
        return Playwright.create(new Playwright.CreateOptions().setEnv(playwrightEnvironment(browserProperties)));
    }

    private Map<String, String> playwrightEnvironment(WebCrawlerProperties.BrowserProperties browserProperties) {
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
                    log.info("Web crawler Playwright browser cache path: {}", browsersPath);
                    playwrightBrowsersPathInfoLogged = true;
                }
            }
        }
        if (!"0".equals(browsersPath) && !Files.isDirectory(Path.of(browsersPath)) && !playwrightBrowsersPathWarningLogged) {
            synchronized (this) {
                if (!playwrightBrowsersPathWarningLogged) {
                    log.warn("Configured web crawler Playwright browser cache path does not exist yet: {}", browsersPath);
                    playwrightBrowsersPathWarningLogged = true;
                }
            }
        }
    }

    private void logPlaywrightSkipDownload(String browsersPath) {
        if (!playwrightSkipDownloadInfoLogged) {
            synchronized (this) {
                if (!playwrightSkipDownloadInfoLogged) {
                    log.info("Web crawler Playwright browser download skipped because cached Chromium was found: {}", browsersPath);
                    playwrightSkipDownloadInfoLogged = true;
                }
            }
        }
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

    private com.microsoft.playwright.options.Proxy playwrightProxy(WebCrawlerProperties.ProxyConfig proxyConfig) {
        if (!proxyUsable(proxyConfig)) {
            return null;
        }
        String type = firstNonBlank(proxyConfig.getType(), "HTTP").toLowerCase(Locale.ROOT);
        String scheme = type.contains("socks") ? "socks5" : "http";
        com.microsoft.playwright.options.Proxy proxy =
            new com.microsoft.playwright.options.Proxy(scheme + "://" + proxyConfig.getHost().trim() + ":" + proxyConfig.getPort());
        if (proxyConfig.getUsername() != null && !proxyConfig.getUsername().isBlank()) {
            proxy.setUsername(proxyConfig.getUsername());
            proxy.setPassword(firstNonBlank(proxyConfig.getPassword(), ""));
        }
        return proxy;
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
