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
