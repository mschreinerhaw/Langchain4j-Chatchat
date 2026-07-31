package com.chatchat.runtime.news.search;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TencentWebSearchClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void signsSearchProAndParsesStringEncodedPages() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                {"Response":{"Pages":[
                  "{\\"title\\":\\"北京热点\\",\\"url\\":\\"https://example.com/beijing\\",\\"date\\":\\"2026/07/31 08:00:00\\",\\"passage\\":\\"热点摘要\\",\\"site\\":\\"示例媒体\\",\\"score\\":0.88}"
                ],"Query":"北京今日热点","Version":"standard","RequestId":"req-1"}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getWebSearch().setEnabled(true);
        properties.getWebSearch().setEndpoint("http://localhost:" + server.getAddress().getPort());
        properties.getWebSearch().setSecretId("test-id");
        properties.getWebSearch().setSecretKey("test-key");
        TencentWebSearchClient client = new TencentWebSearchClient(mapper, properties);

        TencentWebSearchClient.SearchResponse result = client.search("北京今日热点", 5);

        assertThat(authorization.get()).startsWith("TC3-HMAC-SHA256 Credential=test-id/");
        assertThat(requestBody.get().path("Query").asText()).isEqualTo("北京今日热点");
        assertThat(requestBody.get().path("Mode").asInt()).isEqualTo(2);
        assertThat(requestBody.get().has("Cnt")).isFalse();
        assertThat(requestBody.get().has("FromTime")).isTrue();
        assertThat(requestBody.get().has("ToTime")).isTrue();
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.pages()).singleElement().satisfies(page -> {
            assertThat(page.title()).isEqualTo("北京热点");
            assertThat(page.url()).isEqualTo("https://example.com/beijing");
            assertThat(page.score()).isEqualTo(0.88);
        });
    }
}
