package com.chatchat.tools.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebCrawlerServiceFallbackTest {

    private HttpServer server;
    private String pageUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = "<html><head><title>Fallback page</title></head>"
                .concat("<body><main>Java fallback content is available.</main></body></html>")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        pageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToJavaWhenBrowserIsUnavailable() {
        WebCrawlerProperties properties = properties();
        properties.getBrowser().setEnabled(false);
        WebCrawlerService service = service(properties, WebToolCache.NOOP);

        Map<String, Object> result = service.crawl(pageUrl, "browser", true, 3000);

        assertThat(result)
            .containsEntry("crawlMode", "java")
            .containsEntry("requestedCrawlMode", "browser")
            .containsEntry("browserFallbackUsed", true);
        assertThat(result.get("content")).asString().contains("Java fallback content");
        assertThat(result.get("browserFallbacks")).asList().isNotEmpty();
    }

    @Test
    void continuesWithoutCacheWhenCacheReadAndWriteFail() {
        WebCrawlerProperties properties = properties();
        WebToolCache unavailableCache = new WebToolCache() {
            @Override
            public CacheLookup get(String namespace, String cacheKey) {
                throw new IllegalStateException("cache unavailable");
            }

            @Override
            public void put(String namespace, String cacheKey, Map<String, Object> data, long ttlSeconds) {
                throw new IllegalStateException("cache unavailable");
            }
        };
        WebCrawlerService service = service(properties, unavailableCache);

        Map<String, Object> result = service.crawl(pageUrl, "java", false, 3000);

        assertThat(result)
            .containsEntry("crawlMode", "java")
            .containsEntry("cacheReadFallbackUsed", true)
            .containsEntry("cacheWriteFailed", true);
        assertThat(result.get("content")).asString().contains("Java fallback content");
    }

    private WebCrawlerService service(WebCrawlerProperties properties, WebToolCache cache) {
        return new WebCrawlerService(properties, new WebContentProcessor(properties), cache);
    }

    private WebCrawlerProperties properties() {
        WebCrawlerProperties properties = new WebCrawlerProperties();
        properties.setMaxFollowUrls(0);
        properties.setIncludeHtml(false);
        properties.setBrowserFallbackToJava(true);
        return properties;
    }
}
