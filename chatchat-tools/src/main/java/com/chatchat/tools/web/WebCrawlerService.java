package com.chatchat.tools.web;

import com.chatchat.tools.playwright.PlaywrightBrowserSupport;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
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
    private final AutoDetectParser documentParser = new AutoDetectParser(TikaConfig.getDefaultConfig());

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
        int timeout = timeoutMs < 0 ? properties.getTimeoutMs() : timeoutMs;
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
                output = buildOutput(normalizedUrl, response, effectiveCrawlMode, render, startedAt, proxy, context);
                output.put("requestedCrawlMode", crawlMode);
                output.put("browserFallbackUsed", !crawlMode.equals(effectiveCrawlMode));
                crawlChain.add(crawlChainItem(hop, currentUrl, response, output));
                if (!response.htmlResource()) {
                    break;
                }
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
            .maxBodySize(javaFetchMaxBodySize(normalizedUrl))
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .followRedirects(true);
        applyBrowserLikeHeaders(connection, normalizedUrl);
        applyProxy(connection, proxyConfig);
        org.jsoup.Connection.Response response = connection.execute();
        String responseUrl = response.url() == null
            ? normalizedUrl
            : response.url().toString();
        String contentType = firstNonBlank(response.contentType(), "");
        byte[] body = response.bodyAsBytes();
        if (shouldExtractDocument(responseUrl, contentType)) {
            if (!properties.getDocumentExtraction().isEnabled()) {
                return CrawlResponse.document(
                    responseUrl,
                    "",
                    false,
                    "java_document",
                    contentType,
                    fileNameFromUrl(responseUrl),
                    body.length,
                    "",
                    false,
                    "document extraction is disabled"
                );
            }
            if (body.length > documentMaxBytes()) {
                return CrawlResponse.document(
                    responseUrl,
                    "",
                    false,
                    "java_document",
                    contentType,
                    fileNameFromUrl(responseUrl),
                    body.length,
                    "",
                    false,
                    "document is larger than maxDocumentBytes"
                );
            }
            DocumentExtraction extraction = extractDocumentText(responseUrl, contentType, body);
            return CrawlResponse.document(
                responseUrl,
                extraction.text(),
                false,
                "java_document",
                firstNonBlank(extraction.contentType(), contentType),
                fileNameFromUrl(responseUrl),
                body.length,
                extraction.title(),
                extraction.truncated(),
                extraction.error()
            );
        }
        Document document = response.parse();
        return CrawlResponse.html(responseUrl, document.outerHtml(), false, "java_jsoup", contentType, body.length);
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
            return CrawlResponse.html(page.url(), html, true, "playwright_chromium", "text/html", html.length());
        }
    }

    private Map<String, Object> buildOutput(String requestedUrl,
                                            CrawlResponse response,
                                            String crawlMode,
                                            boolean render,
                                            long startedAt,
                                            WebCrawlerProperties.ProxyConfig proxyConfig,
                                            CrawlRequestContext context) {
        WebContentProcessor.ProcessedContent content = response.documentResource()
            ? contentProcessor.processText(firstNonBlank(response.title(), response.fileName()), response.text())
            : contentProcessor.process(response.url(), response.html());
        String mainText = focusedContent(requestedUrl, response.url(), content.mainText());
        List<String> chunks = content.mainText().equals(mainText) ? content.chunks() : chunk(mainText);
        String query = context == null ? "" : firstNonBlank(context.query(), "");
        List<Map<String, Object>> candidateLinks = response.htmlResource()
            ? candidateDetailLinks(response.html(), response.url(), query)
            : List.of();
        String recommendedFollowUrl = recommendedFollowUrl(query, response.url(), candidateLinks);
        Map<String, Object> quality = qualityReport(response, content, mainText, chunks);
        String summary = buildSummary(mainText);
        List<String> keywords = extractKeywords(content.title(), mainText, query);
        List<Map<String, Object>> evidenceBlocks = evidenceBlocks(response.url(), chunks, query);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("url", response.url());
        output.put("requestedUrl", requestedUrl);
        output.put("title", content.title());
        output.put("content", mainText);
        output.put("main_text", mainText);
        output.put("chunks", chunks);
        output.put("summary", summary);
        output.put("keywords", keywords);
        output.put("evidenceBlocks", evidenceBlocks);
        output.put("evidence_blocks", evidenceBlocks);
        output.put("candidateLinks", candidateLinks);
        output.put("candidate_links", candidateLinks);
        output.put("recommendedActions", recommendedActions(candidateLinks, recommendedFollowUrl));
        output.put("recommended_actions", recommendedActions(candidateLinks, recommendedFollowUrl));
        if (isHttpUrl(recommendedFollowUrl)) {
            output.put("recommendedFollowUrl", recommendedFollowUrl);
        }
        output.put("quality", quality);
        output.put("llmEvidence", llmEvidenceCard(response, content, mainText, summary, keywords, evidenceBlocks, quality));
        output.put("timestamp", Instant.now().toEpochMilli());
        output.put("crawlMode", crawlMode);
        output.put("renderRequested", render || "browser".equals(crawlMode));
        output.put("rendered", response.rendered());
        output.put("renderEngine", response.engine());
        output.put("resourceType", response.resourceType());
        output.put("contentType", response.contentType());
        output.put("fileName", response.fileName());
        output.put("documentExtracted", response.documentResource());
        output.put("documentTextTruncated", response.documentTextTruncated());
        output.put("documentExtractionError", firstNonBlank(response.documentExtractionError(), ""));
        output.put("cacheHit", false);
        output.put("contentLength", mainText.length());
        output.put("contentTruncated", content.truncated());
        output.put("rawContentLength", response.rawContentLength());
        output.put("contentHash", cacheService.hash(mainText));
        output.put("durationMs", Math.max(0L, System.currentTimeMillis() - startedAt));
        output.put("html", response.htmlResource() && properties.isIncludeHtml()
            ? truncate(response.html(), properties.getMaxHtmlChars())
            : "");
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
        if ("browser".equals(crawlMode) && isSupportedDocumentUrl(url)) {
            return "java";
        }
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

    private String buildSummary(String text) {
        if (!evidenceEnabled()) {
            return "";
        }
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return "";
        }
        int limit = Math.max(120, properties.getEvidence().getMaxSummaryChars());
        String[] sentences = normalized.split("(?<=[。！？.!?])\\s+|(?<=[。！？.!?])");
        StringBuilder builder = new StringBuilder();
        for (String sentenceValue : sentences) {
            String sentence = normalizeText(sentenceValue);
            if (sentence.isBlank()) {
                continue;
            }
            if (builder.length() + sentence.length() > limit) {
                break;
            }
            builder.append(sentence);
            if (builder.length() >= limit / 2) {
                break;
            }
        }
        String summary = builder.length() == 0 ? normalized : builder.toString();
        return truncate(summary, limit);
    }

    private List<String> extractKeywords(String title, String text, String query) {
        if (!evidenceEnabled()) {
            return List.of();
        }
        int limit = Math.max(1, properties.getEvidence().getMaxKeywords());
        Set<String> keywords = new LinkedHashSet<>();
        for (String term : queryTerms(query)) {
            if (keywords.size() >= limit) {
                break;
            }
            keywords.add(term);
        }
        String source = String.join(" ", firstNonBlank(title, ""), truncate(firstNonBlank(text, ""), 4000));
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}|[\\u4e00-\\u9fa5]{2,8}|\\d{4,}").matcher(source);
        while (matcher.find() && keywords.size() < limit) {
            String keyword = matcher.group().trim();
            if (!keyword.isBlank() && !isStopKeyword(keyword)) {
                keywords.add(keyword);
            }
        }
        return new ArrayList<>(keywords);
    }

    private List<Map<String, Object>> evidenceBlocks(String url, List<String> chunks, String query) {
        if (!evidenceEnabled() || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        int maxBlocks = Math.max(1, properties.getEvidence().getMaxEvidenceBlocks());
        int maxChars = Math.max(200, properties.getEvidence().getMaxEvidenceBlockChars());
        List<String> terms = queryTerms(query);
        List<Map<String, Object>> candidates = new ArrayList<>();
        int index = 0;
        for (String chunk : chunks) {
            String text = truncate(normalizeText(chunk), maxChars);
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("index", index);
            block.put("url", url);
            block.put("text", text);
            block.put("score", evidenceScore(text, terms, index));
            block.put("source", "web_crawler");
            candidates.add(block);
            index++;
        }
        candidates.sort((left, right) -> {
            int score = Double.compare(
                numberValue(right.get("score"), 0),
                numberValue(left.get("score"), 0)
            );
            if (score != 0) {
                return score;
            }
            return Integer.compare(
                ((Number) left.getOrDefault("index", Integer.MAX_VALUE)).intValue(),
                ((Number) right.getOrDefault("index", Integer.MAX_VALUE)).intValue()
            );
        });
        return candidates.size() <= maxBlocks ? candidates : new ArrayList<>(candidates.subList(0, maxBlocks));
    }

    private List<Map<String, Object>> candidateDetailLinks(String html, String baseUrl, String query) {
        if (html == null || html.isBlank() || !isHttpUrl(baseUrl)) {
            return List.of();
        }
        Document document = Jsoup.parse(html, baseUrl);
        document.select("script,style,noscript,template,svg,canvas,iframe,nav,header,footer,aside,form").remove();
        List<String> terms = queryTerms(query);
        String baseRoot = rootDomain(baseUrl);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> links = new ArrayList<>();
        for (Element link : document.select("a[href]")) {
            String url = link.absUrl("href");
            if (!isHttpUrl(url) || sameUrl(url, baseUrl) || !sameRootDomain(baseRoot, url) || !seen.add(normalizeComparableUrl(url))) {
                continue;
            }
            String text = normalizeText(firstNonBlank(link.text(), firstNonBlank(link.attr("title"), link.attr("aria-label"))));
            if (text.length() < 6 || isLowValueLinkText(text) || isLikelyNonContentUrl(url)) {
                continue;
            }
            double score = candidateLinkScore(url, text, terms);
            if (score < 0.35) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", url);
            item.put("text", truncate(text, 160));
            item.put("title", firstNonBlank(link.attr("title"), ""));
            item.put("type", looksLikeArticleUrl(url) ? "detail" : "candidate");
            item.put("score", round(score));
            links.add(item);
        }
        links.sort((left, right) -> Double.compare(numberValue(right.get("score"), 0), numberValue(left.get("score"), 0)));
        return links.size() <= 20 ? links : new ArrayList<>(links.subList(0, 20));
    }

    private List<Map<String, Object>> recommendedActions(List<Map<String, Object>> candidateLinks, String recommendedFollowUrl) {
        if (candidateLinks == null || candidateLinks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> link : candidateLinks.stream().limit(5).toList()) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("action", "crawl_url");
            action.put("url", link.get("url"));
            action.put("confidence", link.get("score"));
            action.put("reason", isHttpUrl(recommendedFollowUrl) && sameUrl(String.valueOf(link.get("url")), recommendedFollowUrl)
                ? "Auto-follow candidate selected because the query asks for a headline/detail article."
                : "Candidate detail link discovered on the current list/channel page.");
            actions.add(action);
        }
        return actions;
    }

    private String recommendedFollowUrl(String query, String currentUrl, List<Map<String, Object>> candidateLinks) {
        if (!wantsHeadlineOrDetail(query) || candidateLinks == null || candidateLinks.isEmpty()) {
            return null;
        }
        for (Map<String, Object> link : candidateLinks) {
            String url = String.valueOf(link.getOrDefault("url", ""));
            if (isHttpUrl(url) && !sameUrl(url, currentUrl) && numberValue(link.get("score"), 0) >= 0.65) {
                return url;
            }
        }
        return null;
    }

    private boolean wantsHeadlineOrDetail(String query) {
        String value = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return value.contains("头条")
            || value.contains("第一条")
            || value.contains("首条")
            || value.contains("第一篇")
            || value.contains("文章内容")
            || value.contains("正文")
            || value.contains("详情")
            || value.contains("headline")
            || value.contains("top story")
            || value.contains("article");
    }

    private double candidateLinkScore(String url, String text, List<String> terms) {
        double score = looksLikeArticleUrl(url) ? 0.65 : 0.35;
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String lowerText = text.toLowerCase(Locale.ROOT);
        if (lowerUrl.matches(".*\\d{8,}.*")) {
            score += 0.12;
        }
        if (text.length() >= 12) {
            score += 0.08;
        }
        if (terms != null) {
            for (String term : terms) {
                String normalized = term.toLowerCase(Locale.ROOT);
                if (!normalized.isBlank() && (lowerText.contains(normalized) || lowerUrl.contains(normalized))) {
                    score += 0.08;
                }
            }
        }
        return Math.min(1.0, score);
    }

    private boolean looksLikeArticleUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String value = url.toLowerCase(Locale.ROOT);
        return value.matches(".*(/a/|/article/|/detail/|/newsinfo/|/content/).*")
            || value.matches(".*\\d{6,}.*\\.s?html(?:[?#].*)?$")
            || value.matches(".*\\d{6,}.*\\.html(?:[?#].*)?$");
    }

    private boolean isLikelyNonContentUrl(String url) {
        String value = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return value.contains("javascript:")
            || value.contains("/login")
            || value.contains("/register")
            || value.contains("/download")
            || value.contains("/app")
            || value.contains("/client")
            || value.contains("/about")
            || value.contains("/help")
            || value.contains("/feedback")
            || value.contains("/ad")
            || value.matches(".*/news/c[a-z0-9_-]+\\.html(?:[?#].*)?$");
    }

    private boolean isLowValueLinkText(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return value.isBlank()
            || value.matches("(?i)^(更多|全部|首页|上一页|下一页|登录|注册|下载|客户端|app|关注|分享|返回|more|home|login|register)$")
            || value.length() <= 2;
    }

    private Map<String, Object> qualityReport(CrawlResponse response,
                                              WebContentProcessor.ProcessedContent content,
                                              String mainText,
                                              List<String> chunks) {
        Map<String, Object> quality = new LinkedHashMap<>();
        String text = firstNonBlank(mainText, "");
        int contentLength = text.length();
        int minUseful = Math.max(20, properties.getEvidence().getMinUsefulTextChars());
        List<String> issues = new ArrayList<>();
        if (contentLength == 0) {
            issues.add("empty_content");
        } else if (contentLength < minUseful) {
            issues.add("short_content");
        }
        if (content.truncated() || response.documentTextTruncated()) {
            issues.add("truncated");
        }
        if (response.documentExtractionError() != null && !response.documentExtractionError().isBlank()) {
            issues.add("document_extraction_error");
        }
        if (looksLikeBlockedPage(text)) {
            issues.add("possible_anti_crawl_or_verification_page");
        }
        if (chunks == null || chunks.isEmpty()) {
            issues.add("no_chunks");
        }

        double readability = readabilityScore(response, text);
        double completeness = (content.truncated() || response.documentTextTruncated()) ? 0.65 : 1.0;
        double usability = 0.45 * readability + 0.35 * completeness + 0.20 * (issues.isEmpty() ? 1.0 : Math.max(0.0, 1.0 - issues.size() * 0.25));
        boolean usable = contentLength >= minUseful
            && !looksLikeBlockedPage(text)
            && (response.documentExtractionError() == null || response.documentExtractionError().isBlank());

        quality.put("usable", usable);
        quality.put("status", usable ? "usable" : "needs_review");
        quality.put("readability_score", round(readability));
        quality.put("completeness_score", round(completeness));
        quality.put("usability_score", round(usability));
        quality.put("content_length", contentLength);
        quality.put("chunk_count", chunks == null ? 0 : chunks.size());
        quality.put("issues", issues);
        return quality;
    }

    private Map<String, Object> llmEvidenceCard(CrawlResponse response,
                                                WebContentProcessor.ProcessedContent content,
                                                String mainText,
                                                String summary,
                                                List<String> keywords,
                                                List<Map<String, Object>> evidenceBlocks,
                                                Map<String, Object> quality) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("url", response.url());
        evidence.put("title", content.title());
        evidence.put("summary", summary);
        evidence.put("resource_type", response.resourceType());
        evidence.put("content_type", response.contentType());
        evidence.put("language", detectLanguage(mainText));
        evidence.put("keywords", keywords);
        evidence.put("quality", quality);
        evidence.put("evidence_blocks", evidenceBlocks);
        evidence.put("citation_count", evidenceBlocks == null ? 0 : evidenceBlocks.size());
        evidence.put("document_extraction_error", firstNonBlank(response.documentExtractionError(), ""));
        return evidence;
    }

    private double evidenceScore(String text, List<String> terms, int index) {
        double score = Math.max(0, 100 - index);
        String lower = text.toLowerCase(Locale.ROOT);
        if (terms != null) {
            for (String term : terms) {
                String normalized = term.toLowerCase(Locale.ROOT);
                if (!normalized.isBlank() && lower.contains(normalized)) {
                    score += 20;
                }
            }
        }
        score += Math.min(20, text.length() / 80.0);
        return round(score);
    }

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

    private boolean evidenceEnabled() {
        return properties.getEvidence() != null && properties.getEvidence().isEnabled();
    }

    private boolean isStopKeyword(String keyword) {
        String value = keyword.toLowerCase(Locale.ROOT);
        return Set.of("http", "https", "www", "com", "html", "index", "the", "and", "for", "with").contains(value);
    }

    private boolean looksLikeBlockedPage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("captcha")
            || lower.contains("verify you are human")
            || lower.contains("access denied")
            || lower.contains("too many requests")
            || lower.contains("forbidden")
            || lower.contains("人机验证")
            || lower.contains("访问过于频繁")
            || lower.contains("验证码");
    }

    private double readabilityScore(CrawlResponse response, String text) {
        int textLength = text == null ? 0 : text.length();
        if (textLength <= 0) {
            return 0.0;
        }
        if (response.documentResource()) {
            return textLength >= Math.max(20, properties.getEvidence().getMinUsefulTextChars()) ? 1.0 : 0.35;
        }
        int htmlLength = response.html() == null ? 0 : response.html().length();
        if (htmlLength <= 0) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, textLength / (double) htmlLength * 4.0));
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

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
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

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
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
        item.put("resourceType", response.resourceType());
        item.put("contentType", response.contentType());
        item.put("fileName", response.fileName());
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
        String recommendedFollowUrl = output == null ? null : String.valueOf(output.getOrDefault("recommendedFollowUrl", ""));
        if (isHttpUrl(recommendedFollowUrl) && !sameUrl(recommendedFollowUrl, baseUrl)) {
            return recommendedFollowUrl;
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

    private boolean sameRootDomain(String baseRoot, String url) {
        if (baseRoot == null || baseRoot.isBlank()) {
            return false;
        }
        return baseRoot.equals(rootDomain(url));
    }

    private String rootDomain(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            String[] parts = normalized.split("\\.");
            if (parts.length <= 2) {
                return normalized;
            }
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        } catch (Exception ex) {
            return "";
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

    private int javaFetchMaxBodySize(String url) {
        int htmlBytes = Math.max(1024, properties.getMaxBodyBytes());
        return Math.max(htmlBytes, documentMaxBytes());
    }

    private int documentMaxBytes() {
        WebCrawlerProperties.DocumentExtractionProperties documentProperties = properties.getDocumentExtraction();
        int configured = documentProperties == null ? 0 : documentProperties.getMaxDocumentBytes();
        return Math.max(1024, configured <= 0 ? properties.getMaxBodyBytes() : configured);
    }

    private boolean shouldExtractDocument(String url, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            return false;
        }
        return isSupportedDocumentUrl(url) || isSupportedDocumentContentType(contentType);
    }

    private boolean isSupportedDocumentUrl(String url) {
        String extension = fileExtension(url);
        if (extension == null || extension.isBlank()) {
            return false;
        }
        WebCrawlerProperties.DocumentExtractionProperties documentProperties = properties.getDocumentExtraction();
        List<String> supported = documentProperties == null ? List.of() : documentProperties.getSupportedExtensions();
        if (supported == null || supported.isEmpty()) {
            return false;
        }
        return supported.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(item -> item.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
            .anyMatch(extension::equals);
    }

    private boolean isSupportedDocumentContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String value = contentType.toLowerCase(Locale.ROOT);
        return value.contains("application/pdf")
            || value.contains("msword")
            || value.contains("officedocument")
            || value.contains("vnd.ms-excel")
            || value.contains("vnd.ms-powerpoint")
            || value.contains("text/csv")
            || value.contains("application/rtf")
            || value.contains("text/rtf");
    }

    private DocumentExtraction extractDocumentText(String url, String contentType, byte[] body) {
        if (body == null || body.length == 0) {
            return new DocumentExtraction("", fileNameFromUrl(url), contentType, false, "document body is empty");
        }
        int writeLimit = documentTextLimit();
        BodyContentHandler handler = new BodyContentHandler(writeLimit);
        Metadata metadata = new Metadata();
        String fileName = fileNameFromUrl(url);
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        if (contentType != null && !contentType.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(body)) {
            documentParser.parse(input, handler, metadata, new ParseContext());
            return new DocumentExtraction(
                normalizeExtractedText(handler.toString()),
                firstNonBlank(metadata.get(TikaCoreProperties.TITLE), fileName),
                firstNonBlank(metadata.get(Metadata.CONTENT_TYPE), contentType),
                false,
                ""
            );
        } catch (SAXException ex) {
            String text = normalizeExtractedText(handler.toString());
            if (!text.isBlank() && isWriteLimitReached(ex)) {
                return new DocumentExtraction(
                    text,
                    firstNonBlank(metadata.get(TikaCoreProperties.TITLE), fileName),
                    firstNonBlank(metadata.get(Metadata.CONTENT_TYPE), contentType),
                    true,
                    ""
                );
            }
            return new DocumentExtraction(
                text,
                firstNonBlank(metadata.get(TikaCoreProperties.TITLE), fileName),
                firstNonBlank(metadata.get(Metadata.CONTENT_TYPE), contentType),
                false,
                ex.getMessage()
            );
        } catch (IOException | TikaException ex) {
            return new DocumentExtraction(
                normalizeExtractedText(handler.toString()),
                firstNonBlank(metadata.get(TikaCoreProperties.TITLE), fileName),
                firstNonBlank(metadata.get(Metadata.CONTENT_TYPE), contentType),
                false,
                ex.getMessage()
            );
        }
    }

    private int documentTextLimit() {
        WebCrawlerProperties.DocumentExtractionProperties documentProperties = properties.getDocumentExtraction();
        int configured = documentProperties == null ? 0 : documentProperties.getMaxExtractedChars();
        return Math.max(1000, configured > 0 ? configured : properties.getMaxTextChars());
    }

    private String normalizeExtractedText(String value) {
        return value == null ? "" : value.replaceAll("[\\t\\x0B\\f\\r ]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private boolean isWriteLimitReached(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName().toLowerCase(Locale.ROOT);
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (className.contains("writelimit") || message.contains("write limit")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String fileNameFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String path = URI.create(url.trim()).getPath();
            if (path == null || path.isBlank()) {
                return "";
            }
            int slash = path.lastIndexOf('/');
            String fileName = slash >= 0 ? path.substring(slash + 1) : path;
            return java.net.URLDecoder.decode(fileName, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    private String fileExtension(String url) {
        String fileName = fileNameFromUrl(url);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int limit = Math.max(0, maxChars);
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record DocumentExtraction(String text,
                                      String title,
                                      String contentType,
                                      boolean truncated,
                                      String error) {
    }

    private record CrawlResponse(String url,
                                 String text,
                                 String html,
                                 boolean rendered,
                                 String engine,
                                 String contentType,
                                 String resourceType,
                                 String fileName,
                                 long rawContentLength,
                                 String title,
                                 boolean documentTextTruncated,
                                 String documentExtractionError) {

        private static CrawlResponse html(String url,
                                          String html,
                                          boolean rendered,
                                          String engine,
                                          String contentType,
                                          long rawContentLength) {
            return new CrawlResponse(
                url,
                "",
                html,
                rendered,
                engine,
                contentType,
                "html",
                "",
                rawContentLength,
                "",
                false,
                ""
            );
        }

        private static CrawlResponse document(String url,
                                              String text,
                                              boolean rendered,
                                              String engine,
                                              String contentType,
                                              String fileName,
                                              long rawContentLength,
                                              String title,
                                              boolean documentTextTruncated,
                                              String documentExtractionError) {
            return new CrawlResponse(
                url,
                text,
                "",
                rendered,
                engine,
                contentType,
                "document",
                fileName,
                rawContentLength,
                title,
                documentTextTruncated,
                documentExtractionError
            );
        }

        private boolean htmlResource() {
            return "html".equals(resourceType);
        }

        private boolean documentResource() {
            return "document".equals(resourceType);
        }
    }

    public record CrawlRequestContext(String tenantId, String taskId, String agentId, String query) {

        public static CrawlRequestContext empty() {
            return new CrawlRequestContext(null, null, null, null);
        }
    }
}
