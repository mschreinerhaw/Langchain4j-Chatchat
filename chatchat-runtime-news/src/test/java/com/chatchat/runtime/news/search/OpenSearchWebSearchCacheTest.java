package com.chatchat.runtime.news.search;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchWebSearchCacheTest {
    private HttpServer server;
    private OpenSearchWebSearchCache cache;

    @AfterEach
    void stop() throws Exception {
        if (cache != null) cache.close();
        if (server != null) server.stop(0);
    }

    @Test
    void findsHighlyRelatedCachedResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = """
                {"hits":{"hits":[{"_source":{
                  "query":"Hangzhou West Lake current hotspots",
                  "fetchedAt":"2026-07-31T00:00:00Z",
                  "response":{"pages":[{"title":"West Lake","url":"https://example.com/west-lake",
                    "date":"2026-07-31","snippet":"Current events","site":"Example","score":0.9}],
                    "requestId":"cached-1","version":"standard"}
                }}]}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        cache = cache();

        var found = cache.findHighlyRelated("Hangzhou West Lake hotspots");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().similarity()).isGreaterThanOrEqualTo(0.72D);
        assertThat(found.orElseThrow().response().requestId()).isEqualTo("cached-1");
    }

    @Test
    void writesResponseToDedicatedDailyIndex() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        cache = cache();
        var response = new TencentWebSearchClient.SearchResponse(List.of(
            new TencentWebSearchClient.SearchPage("Title", "https://example.com", "2026-07-31",
                "Snippet", "Example", 0.9D)), "request-1", "standard");

        cache.put("query", response);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).startsWith("PUT /runtime-web-search-cache-");
        assertThat(requests.get(1)).contains("/_doc/");
    }

    @Test
    void similarityRequiresStrongQueryOverlap() {
        assertThat(OpenSearchWebSearchCache.similarity(
            "Hangzhou West Lake hotspots", "Hangzhou West Lake current hotspots")).isGreaterThan(0.72D);
        assertThat(OpenSearchWebSearchCache.similarity(
            "Hangzhou West Lake hotspots", "Java virtual threads tutorial")).isLessThan(0.30D);
    }

    @Test
    void deletesOnlyDailyIndicesOutsideSevenDayWindow() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            String body = path.startsWith("/_cat/indices/")
                ? "[{\"index\":\"runtime-web-search-cache-2026.07.24\"},"
                    + "{\"index\":\"runtime-web-search-cache-2026.07.25\"}]"
                : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        NewsRuntimeProperties properties = properties();
        cache = new OpenSearchWebSearchCache(properties, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

        assertThat(cache.deleteExpiredIndices())
            .containsExactly("runtime-web-search-cache-2026.07.24");
        assertThat(requests).contains("DELETE /runtime-web-search-cache-2026.07.24")
            .doesNotContain("DELETE /runtime-web-search-cache-2026.07.25");
    }

    private OpenSearchWebSearchCache cache() {
        return new OpenSearchWebSearchCache(properties(), new ObjectMapper());
    }

    private NewsRuntimeProperties properties() {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEndpoint("http://localhost:" + server.getAddress().getPort());
        properties.getWebSearch().getCache().setMinimumSimilarity(0.72D);
        return properties;
    }
}
