package com.chatchat.mcpserver.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class McpEmbeddingClient {

    private final LuceneSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    boolean enabled() {
        LuceneSearchProperties.OpenSearch.Embedding config = config();
        return properties != null
            && properties.isOpenSearchEngine()
            && config.isEnabled()
            && hasText(config.getEndpoint())
            && !config.getEndpoint().contains("{WorkspaceId}")
            && hasText(config.getApiKey())
            && hasText(config.getModel())
            && config.getDimension() > 0;
    }

    boolean configured() {
        LuceneSearchProperties.OpenSearch.Embedding config = config();
        return config.isEnabled()
            && hasText(config.getVectorField())
            && config.getDimension() > 0;
    }

    List<Float> embed(String input) {
        if (!enabled() || !hasText(input)) {
            return List.of();
        }
        LuceneSearchProperties.OpenSearch.Embedding config = config();
        String text = truncate(input.trim(), Math.max(1, config.getMaxInputChars()));
        Map<String, Object> body = Map.of(
            "model", config.getModel(),
            "input", text
        );
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getEndpoint().trim()))
                .timeout(Duration.ofMillis(Math.max(1, openSearchConfig().getRequestTimeoutMs())))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("MCP embedding request failed status={} body={}", response.statusCode(), response.body());
                return List.of();
            }
            List<Float> vector = parseEmbedding(objectMapper.readTree(response.body()));
            if (vector.isEmpty()) {
                log.warn("MCP embedding response has no vector model={}", config.getModel());
                return List.of();
            }
            if (vector.size() != config.getDimension()) {
                log.warn("MCP embedding dimension mismatch expected={} actual={} model={}",
                    config.getDimension(), vector.size(), config.getModel());
                return List.of();
            }
            return vector;
        } catch (IOException ex) {
            log.warn("MCP embedding request failed endpoint={} error={}", config.getEndpoint(), ex.getMessage(), ex);
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("MCP embedding request interrupted endpoint={}", config.getEndpoint(), ex);
            return List.of();
        }
    }

    private List<Float> parseEmbedding(JsonNode root) {
        JsonNode embedding = root.path("data").isArray()
            ? root.path("data").path(0).path("embedding")
            : root.path("embedding");
        if (!embedding.isArray()) {
            embedding = root.path("output").path("embeddings").path(0).path("embedding");
        }
        if (!embedding.isArray()) {
            return List.of();
        }
        List<Float> values = new ArrayList<>();
        for (JsonNode item : embedding) {
            if (item.isNumber()) {
                values.add((float) item.asDouble());
            }
        }
        return values;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize MCP embedding payload", ex);
        }
    }

    private LuceneSearchProperties.OpenSearch.Embedding config() {
        LuceneSearchProperties.OpenSearch openSearch = openSearchConfig();
        return openSearch.getEmbedding() == null
            ? new LuceneSearchProperties.OpenSearch.Embedding()
            : openSearch.getEmbedding();
    }

    private LuceneSearchProperties.OpenSearch openSearchConfig() {
        return properties == null || properties.getOpenSearch() == null
            ? new LuceneSearchProperties.OpenSearch()
            : properties.getOpenSearch();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
