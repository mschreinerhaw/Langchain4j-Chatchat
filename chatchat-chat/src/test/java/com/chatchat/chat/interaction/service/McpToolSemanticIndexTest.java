package com.chatchat.chat.interaction.service;

import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.OpenSearchEmbeddingClient;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpToolSemanticIndexTest {
    @Test
    void indexesAndSearchesOnlyTheDatabaseAllowedToolIds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> searchBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = "{}";
            int status = 200;
            if ("HEAD".equals(method)) status = 404;
            else if (path.endsWith("/_search")) {
                searchBody.set(body);
                response = "{\"hits\":{\"hits\":[{\"_source\":{\"toolId\":\"allowed-id\"}}]}}";
            } else if (path.equals("/_bulk")) response = "{\"errors\":false}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, "HEAD".equals(method) ? -1 : bytes.length);
            if (!"HEAD".equals(method)) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            SearchProperties properties = new SearchProperties();
            properties.getOpenSearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            McpToolSemanticIndex index = new McpToolSemanticIndex(properties,
                mock(OpenSearchEmbeddingClient.class), new SearchTokenizer(),
                new ObjectMapper(), new MockEnvironment());
            McpToolAsset allowed = new McpToolAsset();
            allowed.setId("allowed-id");
            allowed.setLocalToolName("customer_asset_query");
            allowed.setRemoteToolName("customer_asset_query");
            allowed.setDescription("Find customer assets");

            assertThat(index.rank("customer assets", List.of(allowed), 3))
                .containsExactly("customer_asset_query");
            assertThat(searchBody.get()).contains("allowed-id").doesNotContain("forbidden-id");
        } finally {
            server.stop(0);
        }
    }
}
