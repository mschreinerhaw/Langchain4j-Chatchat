package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible embedding client owned by the independently deployed News Runtime. */
@Component
public class NewsEmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(NewsEmbeddingClient.class);

    private final NewsRuntimeProperties.Embedding properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public NewsEmbeddingClient(NewsRuntimeProperties runtimeProperties, ObjectMapper mapper) {
        this(runtimeProperties.getOpenSearch().getEmbedding(), mapper, HttpClient.newHttpClient());
    }

    NewsEmbeddingClient(NewsRuntimeProperties.Embedding properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
    }

    public boolean enabled() {
        return properties.isEnabled() && text(properties.getEndpoint()) && text(properties.getApiKey())
            && text(properties.getModel()) && properties.getDimension() > 0;
    }

    public List<Float> embed(String input) {
        List<List<Float>> values = embedAll(List.of(input));
        return values.isEmpty() ? List.of() : values.get(0);
    }

    public List<List<Float>> embedAll(List<String> inputs) {
        if (!enabled() || inputs == null || inputs.isEmpty()) return List.of();
        List<String> normalized = inputs.stream().map(this::normalize).toList();
        try {
            String payload = mapper.writeValueAsString(Map.of("model", properties.getModel(), "input", normalized));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEndpoint().trim()))
                .timeout(Duration.ofMillis(Math.max(1, properties.getRequestTimeoutMillis())))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey().trim())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("News embedding request failed status={}", response.statusCode());
                return List.of();
            }
            List<List<Float>> vectors = parse(mapper.readTree(response.body()));
            if (vectors.size() != inputs.size() || vectors.stream().anyMatch(v -> v.size() != properties.getDimension())) {
                log.warn("News embedding response shape mismatch expectedCount={} expectedDimension={}",
                    inputs.size(), properties.getDimension());
                return List.of();
            }
            return vectors;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("News embedding request interrupted", ex);
            return List.of();
        } catch (Exception ex) {
            log.warn("News embedding request failed endpoint={} error={}", properties.getEndpoint(), ex.getMessage());
            return List.of();
        }
    }

    private List<List<Float>> parse(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            JsonNode single = root.path("embedding");
            if (!single.isArray()) single = root.path("output").path("embeddings").path(0).path("embedding");
            return single.isArray() ? List.of(vector(single)) : List.of();
        }
        List<List<Float>> result = new ArrayList<>();
        for (JsonNode item : data) result.add(vector(item.path("embedding")));
        return result;
    }

    private List<Float> vector(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Float> values = new ArrayList<>();
        node.forEach(value -> { if (value.isNumber()) values.add((float) value.asDouble()); });
        return values;
    }

    private String normalize(String value) {
        String input = value == null ? "" : value.trim();
        int max = Math.max(1, properties.getMaxInputChars());
        return input.length() <= max ? input : input.substring(0, max);
    }

    private boolean text(String value) { return value != null && !value.isBlank(); }
}
