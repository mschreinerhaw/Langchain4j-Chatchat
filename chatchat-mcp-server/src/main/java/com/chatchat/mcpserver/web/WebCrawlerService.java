package com.chatchat.mcpserver.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WebCrawlerService {

    private final WebCrawlerProperties properties;
    private final WebContentProcessor contentProcessor;
    private final WebPageCacheService cacheService;

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
        if (!properties.isEnabled()) {
            throw new IllegalStateException("web crawler is disabled");
        }
        String normalizedUrl = normalizeUrl(url);
        WebPageCacheService.CacheLookup cache = cacheService.get("page", normalizedUrl);
        if (cache.hit()) {
            Map<String, Object> cached = new LinkedHashMap<>(cache.data());
            cached.put("cacheHit", true);
            cached.put("cacheAgeSeconds", cache.ageSeconds());
            return cached;
        }

        long startedAt = System.currentTimeMillis();
        int timeout = timeoutMs > 0 ? timeoutMs : properties.getTimeoutMs();
        try {
            Document document = Jsoup.connect(normalizedUrl)
                .userAgent(properties.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", properties.getAcceptLanguage())
                .timeout(Math.max(1000, timeout))
                .maxBodySize(Math.max(1024, properties.getMaxBodyBytes()))
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .followRedirects(true)
                .get();
            String html = document.outerHtml();
            WebContentProcessor.ProcessedContent content = contentProcessor.process(normalizedUrl, html);
            String responseUrl = document.location() == null || document.location().isBlank()
                ? normalizedUrl
                : document.location();

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("url", responseUrl);
            output.put("requestedUrl", normalizedUrl);
            output.put("title", content.title());
            output.put("content", content.mainText());
            output.put("main_text", content.mainText());
            output.put("chunks", content.chunks());
            output.put("keywords", content.keywords());
            output.put("timestamp", Instant.now().toEpochMilli());
            output.put("renderRequested", render);
            output.put("rendered", false);
            output.put("renderEngine", "jsoup");
            output.put("cacheHit", false);
            output.put("contentLength", content.mainText().length());
            output.put("contentHash", cacheService.hash(content.mainText()));
            output.put("durationMs", Math.max(0L, System.currentTimeMillis() - startedAt));
            output.put("html", properties.isIncludeHtml() ? truncate(html, properties.getMaxHtmlChars()) : "");
            cacheService.put("page", normalizedUrl, output, properties.getCacheTtlSeconds());
            return output;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to crawl URL: " + ex.getMessage(), ex);
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

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int limit = Math.max(0, maxChars);
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
