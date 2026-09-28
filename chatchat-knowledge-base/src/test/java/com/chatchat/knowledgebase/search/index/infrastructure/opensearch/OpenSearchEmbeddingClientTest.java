package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.config.SearchProperties;
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

class OpenSearchEmbeddingClientTest {

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

        SearchProperties properties = embeddingProperties(2);
        OpenSearchEmbeddingClient client = new OpenSearchEmbeddingClient(properties, objectMapper);

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
                status = 400;
                response = "{\"error\":\"dimensions is not supported\"}".getBytes(StandardCharsets.UTF_8);
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

        OpenSearchEmbeddingClient client = new OpenSearchEmbeddingClient(embeddingProperties(2), objectMapper);

        assertThat(client.embed("fallback test")).containsExactly(0.1F, 0.2F);
        assertThat(requestCount).hasValue(2);
        assertThat(retryBody.get().has("dimensions")).isFalse();
    }

    @Test
    void neverModeOmitsDimensionParameter() throws Exception {
        AtomicReference<JsonNode> requestBody = successfulServer();
        SearchProperties properties = embeddingProperties(2);
        properties.getOpenSearch().getEmbedding().setDimensionRequestMode(
            SearchProperties.OpenSearch.Embedding.DimensionRequestMode.NEVER);

        assertThat(new OpenSearchEmbeddingClient(properties, objectMapper).embed("native test"))
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

    private SearchProperties embeddingProperties(int dimension) {
        SearchProperties properties = new SearchProperties();
        properties.setEngine("opensearch");
        SearchProperties.OpenSearch openSearch = new SearchProperties.OpenSearch();
        SearchProperties.OpenSearch.Embedding embedding = new SearchProperties.OpenSearch.Embedding();
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
