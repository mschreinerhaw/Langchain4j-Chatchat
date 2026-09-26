package com.chatchat.mcpserver.search.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpEmbeddingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsConfiguredDimensionToOpenAiCompatibleEndpoint() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/embeddings", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"data\":[{\"embedding\":[0.1,0.2]}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        LuceneSearchProperties properties = embeddingProperties(2);
        McpEmbeddingClient client = new McpEmbeddingClient(properties, objectMapper);

        assertThat(client.embed("dimension test")).containsExactly(0.1F, 0.2F);
        assertThat(requestBody.get().path("model").asText()).isEqualTo("test-model");
        assertThat(requestBody.get().path("input").asText()).isEqualTo("dimension test");
        assertThat(requestBody.get().path("dimensions").asInt()).isEqualTo(2);
    }

    @Test
    void autoRetriesWithoutDimensionWhenEndpointRejectsParameter() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<JsonNode> retryBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/embeddings", exchange -> {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            int attempt = requestCount.incrementAndGet();
            byte[] response;
            int status;
            if (attempt == 1) {
                assertThat(body.has("dimensions")).isTrue();
                status = 422;
                response = "{\"error\":\"unknown field dimensions\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                retryBody.set(body);
                status = 200;
                response = "{\"data\":[{\"embedding\":[0.1,0.2]}]}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        McpEmbeddingClient client = new McpEmbeddingClient(embeddingProperties(2), objectMapper);

        assertThat(client.embed("fallback test")).containsExactly(0.1F, 0.2F);
        assertThat(requestCount).hasValue(2);
        assertThat(retryBody.get().has("dimensions")).isFalse();
    }

    @Test
    void neverModeOmitsDimensionParameter() throws Exception {
        AtomicReference<JsonNode> requestBody = successfulServer();
        LuceneSearchProperties properties = embeddingProperties(2);
        properties.getOpenSearch().getEmbedding().setDimensionRequestMode(
            LuceneSearchProperties.OpenSearch.Embedding.DimensionRequestMode.NEVER);

        assertThat(new McpEmbeddingClient(properties, objectMapper).embed("native test"))
            .containsExactly(0.1F, 0.2F);
        assertThat(requestBody.get().has("dimensions")).isFalse();
    }

    private AtomicReference<JsonNode> successfulServer() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/embeddings", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"data\":[{\"embedding\":[0.1,0.2]}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return requestBody;
    }

    private LuceneSearchProperties embeddingProperties(int dimension) {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setEngine("opensearch");
        LuceneSearchProperties.OpenSearch openSearch = new LuceneSearchProperties.OpenSearch();
        LuceneSearchProperties.OpenSearch.Embedding embedding = new LuceneSearchProperties.OpenSearch.Embedding();
        embedding.setEnabled(true);
        embedding.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/embeddings");
        embedding.setApiKey("test-key");
        embedding.setModel("test-model");
        embedding.setDimension(dimension);
        openSearch.setEmbedding(embedding);
        properties.setOpenSearch(openSearch);
        return properties;
    }
}
