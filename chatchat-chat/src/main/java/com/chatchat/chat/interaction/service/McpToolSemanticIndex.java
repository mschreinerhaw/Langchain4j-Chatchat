package com.chatchat.chat.interaction.service;

import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.OpenSearchEmbeddingClient;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;

/** Recall index for published MCP tools. Returned IDs are always intersected with DB scope. */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpToolSemanticIndex {
    private final SearchProperties properties;
    private final OpenSearchEmbeddingClient embeddings;
    private final SearchTokenizer tokenizer;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final Map<String, String> indexed = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean indexReady;
    private volatile RestClient client;

    public List<String> rank(String query, List<McpToolAsset> allowed, int limit) {
        if (allowed == null || allowed.isEmpty()) return List.of();
        int bounded = Math.max(1, Math.min(20, limit));
        List<String> fallback = localRank(query, allowed, bounded);
        if (!enabled() || query == null || query.isBlank()) return fallback;
        try {
            ensureIndex();
            synchronize(allowed);
            List<String> ids = allowed.stream().map(McpToolAsset::getId).toList();
            Map<String, String> namesById = new HashMap<>();
            allowed.forEach(tool -> namesById.put(tool.getId(), tool.getLocalToolName()));
            List<String> lexical = searchIds(Map.of("size", Math.min(100, ids.size()),
                "_source", List.of("toolId"), "query", Map.of("bool", Map.of(
                    "must", List.of(Map.of("multi_match", Map.of("query", query,
                        "fields", List.of("name^5", "description^3", "schema^2", "serviceName")))),
                    "filter", List.of(Map.of("terms", Map.of("toolId", ids)))))), namesById);
            List<String> vector = List.of();
            if (embeddings.configured()) {
                try {
                    List<Float> queryVector = embeddings.embed(query);
                    if (!queryVector.isEmpty()) {
                        vector = searchIds(Map.of("size", Math.min(100, ids.size()),
                            "_source", List.of("toolId"), "query", Map.of("knn", Map.of("vector",
                                Map.of("vector", queryVector, "k", Math.min(100, ids.size()),
                                    "filter", Map.of("terms", Map.of("toolId", ids)))))), namesById);
                    }
                } catch (RuntimeException ex) {
                    log.debug("MCP vector recall unavailable: {}", ex.getMessage());
                }
            }
            Map<String, Double> scores = new HashMap<>();
            fuse(scores, lexical);
            fuse(scores, vector);
            List<String> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                    .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).limit(bounded).toList();
            return ranked.isEmpty() ? fallback : ranked;
        } catch (Exception ex) {
            log.warn("MCP OpenSearch recall unavailable; using DB-scoped local ranking: {}", ex.getMessage());
            return fallback;
        }
    }

    private void fuse(Map<String, Double> scores, List<String> names) {
        for (int i = 0; i < names.size(); i++) scores.merge(names.get(i), 1D / (60 + i + 1), Double::sum);
    }

    private List<String> localRank(String query, List<McpToolAsset> allowed, int limit) {
        List<String> terms = tokenizer.searchTokens(query == null ? "" : query);
        return allowed.stream().map(tool -> Map.entry(tool, score(tool, terms)))
            .filter(entry -> terms.isEmpty() || entry.getValue() > 0)
            .sorted(Map.Entry.<McpToolAsset, Integer>comparingByValue().reversed()
                .thenComparing(entry -> entry.getKey().getLocalToolName()))
            .limit(limit).map(entry -> entry.getKey().getLocalToolName()).toList();
    }

    private int score(McpToolAsset tool, List<String> terms) {
        String name = (tool.getLocalToolName() + " " + tool.getRemoteToolName()).toLowerCase(Locale.ROOT);
        String details = (safe(tool.getDescription()) + " " + safe(tool.getInputSchemaJson())
            + " " + safe(tool.getOutputSchemaJson()) + " " + safe(tool.getServiceName()))
            .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (name.contains(term)) score += 5;
            if (details.contains(term)) score += 2;
        }
        return score;
    }

    private synchronized void ensureIndex() throws Exception {
        if (indexReady) return;
        HttpResult head = request("HEAD", indexPath(), null);
        if (head.statusCode() == 404) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("toolId", Map.of("type", "keyword"));
            fields.put("name", Map.of("type", "text"));
            fields.put("description", Map.of("type", "text"));
            fields.put("schema", Map.of("type", "text"));
            fields.put("serviceName", Map.of("type", "text"));
            fields.put("serviceId", Map.of("type", "keyword"));
            Map<String, Object> body = new LinkedHashMap<>();
            if (embeddings.configured()) {
                fields.put("vector", Map.of("type", "knn_vector", "dimension",
                    properties.getOpenSearch().getEmbedding().getDimension()));
                body.put("settings", Map.of("index", Map.of("knn", true)));
            }
            body.put("mappings", Map.of("properties", fields));
            HttpResult created = request("PUT", indexPath(), mapper.writeValueAsString(body));
            if (created.statusCode() >= 300 && !created.body().contains("resource_already_exists_exception")) {
                throw new IllegalStateException("MCP index creation returned " + created.statusCode());
            }
        } else if (head.statusCode() >= 300) {
            throw new IllegalStateException("MCP index lookup returned " + head.statusCode());
        }
        indexReady = true;
    }

    private synchronized void synchronize(List<McpToolAsset> tools) throws Exception {
        StringBuilder operations = new StringBuilder();
        Map<String, String> changed = new HashMap<>();
        for (McpToolAsset tool : tools) {
            String revision = safe(tool.getUpdatedAt()) + "|" + safe(tool.getLocalToolName())
                + "|" + safe(tool.getRemoteToolName()) + "|" + safe(tool.getServiceName())
                + "|" + safe(tool.getDescription()) + "|" + safe(tool.getInputSchemaJson())
                + "|" + safe(tool.getOutputSchemaJson());
            if (revision.equals(indexed.get(tool.getId()))) continue;
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("toolId", tool.getId());
            document.put("name", tool.getLocalToolName() + " " + tool.getRemoteToolName());
            document.put("description", safe(tool.getDescription()));
            document.put("schema", safe(tool.getInputSchemaJson()) + " " + safe(tool.getOutputSchemaJson()));
            document.put("serviceName", safe(tool.getServiceName()));
            document.put("serviceId", safe(tool.getServiceId()));
            List<Float> vector = embeddings.embed(tool.getLocalToolName() + " " + safe(tool.getDescription()));
            if (!vector.isEmpty()) document.put("vector", vector);
            operations.append(mapper.writeValueAsString(Map.of("index", Map.of("_index", indexName(), "_id", tool.getId()))))
                .append('\n').append(mapper.writeValueAsString(document)).append('\n');
            changed.put(tool.getId(), revision);
        }
        if (changed.isEmpty()) return;
        HttpResult response = request("POST", "/_bulk?refresh=wait_for", operations.toString());
        if (response.statusCode() >= 300 || mapper.readTree(response.body()).path("errors").asBoolean()) {
            throw new IllegalStateException("MCP index update failed");
        }
        indexed.putAll(changed);
    }

    private List<String> searchIds(Map<String, Object> body, Map<String, String> namesById) throws Exception {
        HttpResult response = request("POST", indexPath() + "/_search", mapper.writeValueAsString(body));
        if (response.statusCode() >= 300) throw new IllegalStateException("MCP index search returned " + response.statusCode());
        JsonNode hits = mapper.readTree(response.body()).path("hits").path("hits");
        List<String> names = new ArrayList<>();
        for (JsonNode hit : hits) {
            String name = namesById.get(hit.path("_source").path("toolId").asText());
            if (name != null && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private HttpResult request(String method, String path, String body) throws Exception {
        Request request = new Request(method, path.contains("?") ? path.substring(0, path.indexOf('?')) : path);
        if (path.contains("refresh=wait_for")) request.addParameter("refresh", "wait_for");
        if (body != null) request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        try {
            return result(client().performRequest(request));
        } catch (ResponseException ex) {
            HttpResult failure = result(ex.getResponse());
            if (failure.statusCode() == 404 && !"HEAD".equals(method)) {
                indexReady = false;
                indexed.clear();
            }
            return failure;
        }
    }

    private HttpResult result(Response response) throws IOException {
        return new HttpResult(response.getStatusLine().getStatusCode(),
            response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
    }

    private synchronized RestClient client() {
        if (client != null) return client;
        SearchProperties.OpenSearch config = properties.getOpenSearch();
        URI uri = URI.create(config.getUrl());
        int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), port, uri.getScheme()))
            .setRequestConfigCallback(request -> request
                .setConnectTimeout(Math.max(1, config.getConnectTimeoutMs()))
                .setSocketTimeout(Math.max(1, config.getRequestTimeoutMs())));
        if (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
            builder.setPathPrefix(uri.getPath());
        }
        CredentialsProvider credentials = new BasicCredentialsProvider();
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                config.getUsername(), safe(config.getPassword())));
        }
        builder.setHttpClientConfigCallback(http -> {
            http.setDefaultCredentialsProvider(credentials);
            if (config.isInsecureSsl()) {
                try {
                    SSLContext ssl = SSLContexts.custom().loadTrustMaterial(null,
                        (chain, authType) -> true).build();
                    http.setSSLContext(ssl).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                } catch (Exception ex) {
                    throw new IllegalStateException("Cannot configure MCP OpenSearch TLS", ex);
                }
            }
            return http;
        });
        client = builder.build();
        return client;
    }

    @PreDestroy
    public void close() throws IOException {
        if (client != null) client.close();
    }

    private record HttpResult(int statusCode, String body) { }

    private boolean enabled() {
        return environment.getProperty("chatchat.mcp.capability-retrieval.enabled", Boolean.class, true)
            && properties.getOpenSearch().isEnabled()
            && properties.getOpenSearch().getUrl() != null
            && !properties.getOpenSearch().getUrl().isBlank();
    }

    private String indexPath() { return "/" + indexName(); }
    private String indexName() {
        String configured = environment.getProperty("chatchat.mcp.capability-retrieval.index-name",
            properties.getOpenSearch().getIndexName() + "_mcp_tools_v1");
        return configured
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }
    private String safe(Object value) { return value == null ? "" : String.valueOf(value); }
}
