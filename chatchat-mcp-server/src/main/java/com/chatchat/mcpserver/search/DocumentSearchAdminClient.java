package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentSearchAdminClient {

    private final ObjectMapper objectMapper;
    private final ChatChatMcpServerProperties.DocumentSearchProperties properties;
    private final HttpClient httpClient;
    private volatile String documentSearchToken;

    @Autowired
    public DocumentSearchAdminClient(ObjectMapper objectMapper, ChatChatMcpServerProperties properties) {
        this(
            objectMapper,
            properties == null ? new ChatChatMcpServerProperties.DocumentSearchProperties() : properties.getDocumentSearch(),
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        );
    }

    DocumentSearchAdminClient(ObjectMapper objectMapper,
                              ChatChatMcpServerProperties.DocumentSearchProperties properties,
                              HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new ChatChatMcpServerProperties.DocumentSearchProperties() : properties;
        this.httpClient = httpClient;
    }

    public Map<String, Object> search(Map<String, Object> input, int limit) {
        Map<String, Object> request = documentSearchRequest(input, limit);
        String url = endpointUrl();
        long startedAt = System.currentTimeMillis();
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            URI uri = URI.create(url);
            HttpResponse<String> response = sendDocumentSearchPost(uri, requestBody);
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            return toSearchResult(input, request, url, response.statusCode(), response.body(), durationMs, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return toSearchResult(input, request, url, 0, null, Math.max(0, System.currentTimeMillis() - startedAt), ex.getMessage());
        } catch (Exception ex) {
            return toSearchResult(input, request, url, 0, null, Math.max(0, System.currentTimeMillis() - startedAt), ex.getMessage());
        }
    }

    private Map<String, Object> documentSearchRequest(Map<String, Object> input, int limit) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", text(firstPresent(input, "query", "q", "intentText"), ""));
        request.put("topK", boundedInt(firstPresent(input, "topK", "limit"), limit, 1, 50));
        List<String> fileIds = stringList(firstPresent(input,
            "fileIds",
            "fileIdsText",
            "documentIds",
            "documentIdsText",
            "docIds",
            "docIdsText",
            "selectedFileIds"));
        if (!fileIds.isEmpty()) {
            request.put("fileIds", fileIds);
        }
        copyIfPresent(input, request, "tenantId", "userId");
        List<String> roles = stringList(input.get("roles"));
        if (!roles.isEmpty()) {
            request.put("roles", roles);
        }
        Object filters = input.get("filters");
        if (filters instanceof Map<?, ?> map && !map.isEmpty()) {
            request.put("filters", filters);
        }
        request.put("debug", Boolean.TRUE.equals(input.get("debug")) || "true".equalsIgnoreCase(text(input.get("debug"), "")));
        return request;
    }

    private HttpResponse<String> sendDocumentSearchPost(URI uri, String requestBody) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(buildDocumentSearchPost(uri, requestBody), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if ((response.statusCode() == 401 || responseIndicatesUnauthorized(response.body()))
            && authEnabled()
            && configuredBearerToken().isBlank()) {
            documentSearchToken = null;
            response = httpClient.send(buildDocumentSearchPost(uri, requestBody), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        return response;
    }

    private HttpRequest buildDocumentSearchPost(URI uri, String requestBody) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMillis(properties.getTimeoutMs()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        String token = resolveToken();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    private String resolveToken() throws IOException, InterruptedException {
        if (!authEnabled()) {
            return "";
        }
        String configured = configuredBearerToken();
        if (!configured.isBlank()) {
            return configured;
        }
        if (!hasLoginCredentials()) {
            return "";
        }
        String cached = documentSearchToken;
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        synchronized (this) {
            cached = documentSearchToken;
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
            documentSearchToken = loginForToken();
            return documentSearchToken == null ? "" : documentSearchToken;
        }
    }

    private String loginForToken() throws IOException, InterruptedException {
        ChatChatMcpServerProperties.DocumentSearchProperties.AuthProperties auth = authProperties();
        String body = objectMapper.writeValueAsString(Map.of(
            "username", text(auth.getUsername(), ""),
            "password", text(auth.getPassword(), "")
        ));
        URI loginUri = loginUri();
        HttpRequest loginRequest = HttpRequest.newBuilder(loginUri)
            .timeout(Duration.ofMillis(properties.getTimeoutMs()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(loginRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("document_search login returned HTTP " + response.statusCode() + " from " + loginUri);
        }
        return extractLoginToken(response.body(), loginUri);
    }

    private String extractLoginToken(String body, URI loginUri) throws IOException {
        Map<String, Object> payload = parseBody(body);
        Object code = payload.get("code");
        if (code instanceof Number number && number.intValue() >= 400) {
            throw new IllegalStateException("document_search login returned code " + number.intValue() + " from " + loginUri);
        }
        Map<String, Object> data = asMap(payload.containsKey("data") ? payload.get("data") : payload);
        String token = text(data.get("token"), "");
        if (token.isBlank()) {
            throw new IllegalStateException("document_search login did not return token from " + loginUri);
        }
        return token;
    }

    private URI loginUri() {
        String loginPath = text(authProperties().getLoginPath(), "/api/v1/enterprise/auth/login");
        if (loginPath.startsWith("http://") || loginPath.startsWith("https://")) {
            return URI.create(loginPath);
        }
        return URI.create(trimRight(text(properties.getApiBaseUrl(), "http://localhost:8080"), '/') + "/" + trimLeft(loginPath, '/'));
    }

    private boolean responseIndicatesUnauthorized(String body) {
        Map<String, Object> payload = parseBody(body);
        Object code = payload.get("code");
        if (code instanceof Number number && number.intValue() == 401) {
            return true;
        }
        String message = text(payload.get("message"), "");
        return message.contains("请先登录") || message.toLowerCase(java.util.Locale.ROOT).contains("login");
    }

    private boolean authEnabled() {
        return authProperties().isEnabled();
    }

    private String configuredBearerToken() {
        return text(authProperties().getBearerToken(), "");
    }

    private boolean hasLoginCredentials() {
        ChatChatMcpServerProperties.DocumentSearchProperties.AuthProperties auth = authProperties();
        return !text(auth.getUsername(), "").isBlank() && !text(auth.getPassword(), "").isBlank();
    }

    private ChatChatMcpServerProperties.DocumentSearchProperties.AuthProperties authProperties() {
        return properties.getAuth() == null
            ? new ChatChatMcpServerProperties.DocumentSearchProperties.AuthProperties()
            : properties.getAuth();
    }

    private Map<String, Object> toSearchResult(Map<String, Object> input,
                                               Map<String, Object> documentSearchRequest,
                                               String url,
                                               int statusCode,
                                               String body,
                                               long durationMs,
                                               String exceptionMessage) {
        Map<String, Object> response = parseBody(body);
        Map<String, Object> data = asMap(response.getOrDefault("data", response));
        List<Map<String, Object>> evidence = mapList(data.get("results")).stream()
            .map(this::evidenceRow)
            .toList();
        List<Map<String, Object>> candidateDocs = mapList(data.get("documents")).stream()
            .map(this::candidateDocumentRow)
            .toList();
        List<Map<String, Object>> rows = evidence.isEmpty() ? candidateDocs : evidence;

        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("url", url);
        upstream.put("statusCode", statusCode);
        upstream.put("ok", statusCode >= 200 && statusCode < 300 && !Boolean.FALSE.equals(response.get("success")) && exceptionMessage == null);
        upstream.put("durationMs", durationMs);
        if (exceptionMessage != null && !exceptionMessage.isBlank()) {
            upstream.put("error", exceptionMessage);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexType", "document_search");
        result.put("luceneEnabled", true);
        result.put("request", input);
        result.put("documentSearchRequest", documentSearchRequest);
        result.put("upstream", upstream);
        result.put("query", data.getOrDefault("query", documentSearchRequest.get("query")));
        result.put("intent", data.get("intent"));
        result.put("matchType", data.get("matchType"));
        result.put("retrievalSemantics", data.get("retrievalSemantics"));
        result.put("count", rows.size());
        result.put("evidenceCount", evidence.size());
        result.put("candidateDocCount", candidateDocs.size());
        result.put("results", rows);
        result.put("candidateDocs", candidateDocs);
        result.put("raw", data.isEmpty() ? response : data);
        if (!Boolean.TRUE.equals(upstream.get("ok"))) {
            result.put("error", firstNonBlank(exceptionMessage, text(response.get("message"), ""), "document_search upstream request failed"));
        }
        return result;
    }

    private Map<String, Object> evidenceRow(Map<String, Object> chunk) {
        Map<String, Object> citation = asMap(chunk.get("citation"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", firstNonBlank(text(chunk.get("chunkId"), ""), text(chunk.get("refId"), "")));
        row.put("kind", "document_evidence");
        row.put("assetType", "document");
        row.put("score", chunk.get("score"));
        row.put("source", firstNonBlank(text(citation.get("source"), ""), text(chunk.get("fileName"), "")));
        row.put("documentId", chunk.get("fileId"));
        row.put("name", firstNonBlank(text(chunk.get("fileName"), ""), text(chunk.get("fileId"), "")));
        row.put("title", firstNonBlank(text(chunk.get("section"), ""), text(chunk.get("chunkType"), "")));
        row.put("description", preview(text(chunk.get("content"), "")));
        row.put("refId", chunk.get("refId"));
        row.put("chunkId", chunk.get("chunkId"));
        row.put("fileId", chunk.get("fileId"));
        row.put("fileName", chunk.get("fileName"));
        row.put("section", chunk.get("section"));
        row.put("chunkIndex", chunk.get("chunkIndex"));
        row.put("chunkType", chunk.get("chunkType"));
        row.put("content", chunk.get("content"));
        row.put("highlights", chunk.get("highlights"));
        row.put("citation", citation);
        row.put("trace", chunk.get("trace"));
        return row;
    }

    private Map<String, Object> candidateDocumentRow(Map<String, Object> document) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", firstNonBlank(text(document.get("docId"), ""), text(document.get("fileId"), "")));
        row.put("kind", "document_candidate");
        row.put("assetType", "document");
        row.put("score", document.get("score"));
        row.put("source", "document_search");
        row.put("documentId", firstNonBlank(text(document.get("docId"), ""), text(document.get("fileId"), "")));
        row.put("name", firstNonBlank(text(document.get("fileName"), ""), text(document.get("title"), ""), text(document.get("docId"), "")));
        row.put("title", document.get("title"));
        row.put("description", document.get("documentType"));
        row.put("tags", document.get("tags"));
        return row;
    }

    private Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (IOException ignored) {
            return new LinkedHashMap<>(Map.of("rawBody", body));
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : iterable) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                rows.add(map);
            }
        }
        return rows;
    }

    private String endpointUrl() {
        String base = text(properties.getApiBaseUrl(), "http://localhost:8080");
        String path = text(properties.getEndpointPath(), "/api/v1/search/document-search");
        return trimRight(base, '/') + "/" + trimLeft(path, '/');
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                target.put(key, value);
            }
        }
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values == null ? null : values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private int boundedInt(Object value, int fallback, int min, int max) {
        try {
            int parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(String.valueOf(value).replace('\uFF0C', ',').split("[,;\\s]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private String trimRight(String value, char ch) {
        String current = value == null ? "" : value;
        while (current.endsWith(String.valueOf(ch))) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }

    private String trimLeft(String value, char ch) {
        String current = value == null ? "" : value;
        while (current.startsWith(String.valueOf(ch))) {
            current = current.substring(1);
        }
        return current;
    }
}
