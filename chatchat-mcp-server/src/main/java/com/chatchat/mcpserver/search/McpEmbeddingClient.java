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
import java.util.Collections;
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
        List<List<Float>> vectors = embedAll(List.of(input));
        return vectors.isEmpty() ? List.of() : vectors.get(0);
    }

    List<List<Float>> embedAll(List<String> inputs) {
        if (!enabled()) {
            return emptyVectors(inputs == null ? 0 : inputs.size());
        }
        LuceneSearchProperties.OpenSearch.Embedding config = config();
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<List<Float>> result = new ArrayList<>(Collections.nCopies(inputs.size(), List.of()));
        List<String> requestInputs = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            String input = inputs.get(i);
            if (!hasText(input)) {
                continue;
            }
            requestInputs.add(truncate(input.trim(), Math.max(1, config.getMaxInputChars())));
            positions.add(i);
        }
        if (requestInputs.isEmpty()) {
            return result;
        }
        Object payloadInput = requestInputs.size() == 1 ? requestInputs.get(0) : requestInputs;
        Map<String, Object> body = Map.of(
            "model", config.getModel(),
            "input", payloadInput
        );
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getEndpoint().trim()))
                .timeout(Duration.ofMillis(Math.max(1, config.getRequestTimeoutMs())))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("MCP embedding request failed status={} body={}", response.statusCode(), response.body());
                return result;
            }
            List<List<Float>> vectors = parseEmbeddings(objectMapper.readTree(response.body()));
            if (vectors.isEmpty()) {
                log.warn("MCP embedding response has no vector model={}", config.getModel());
                return result;
            }
            for (int i = 0; i < Math.min(vectors.size(), positions.size()); i++) {
                List<Float> vector = vectors.get(i);
                if (vector.size() != config.getDimension()) {
                    log.warn("MCP embedding dimension mismatch expected={} actual={} model={}",
                        config.getDimension(), vector.size(), config.getModel());
                    continue;
                }
                result.set(positions.get(i), vector);
            }
            return result;
        } catch (IOException ex) {
            log.warn("MCP embedding request failed endpoint={} error={}", config.getEndpoint(), ex.getMessage(), ex);
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("MCP embedding request interrupted endpoint={}", config.getEndpoint(), ex);
            return result;
        }
    }

    private List<List<Float>> parseEmbeddings(JsonNode root) {
        JsonNode data = root.path("data");
        if (data.isArray()) {
            List<List<Float>> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                List<Float> vector = parseVector(item.path("embedding"));
                if (!vector.isEmpty()) {
                    vectors.add(vector);
                }
            }
            return vectors;
        }
        List<Float> vector = parseEmbedding(root);
        return vector.isEmpty() ? List.of() : List.of(vector);
    }

    private List<Float> parseEmbedding(JsonNode root) {
        JsonNode embedding = root.path("data").isArray()
            ? root.path("data").path(0).path("embedding")
            : root.path("embedding");
        if (!embedding.isArray()) {
            embedding = root.path("output").path("embeddings").path(0).path("embedding");
        }
        return parseVector(embedding);
    }

    private List<Float> parseVector(JsonNode embedding) {
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

    private List<List<Float>> emptyVectors(int size) {
        return size <= 0 ? List.of() : new ArrayList<>(Collections.nCopies(size, List.of()));
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
