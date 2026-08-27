package com.chatchat.chat.conversation.store;

import com.chatchat.chat.conversation.model.ChatMessageDetail;

import com.chatchat.chat.conversation.model.Conversation;

import com.chatchat.knowledgebase.search.SearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OpenSearchChatMessageTextStore implements ChatMessageTextStore {

    private static final Pattern INDEX_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final ChatDetailStoreProperties detailProperties;
    private final SearchProperties searchProperties;
    private final ObjectMapper objectMapper;
    private RestClient restClient;
    private volatile boolean available;

    public OpenSearchChatMessageTextStore(ChatDetailStoreProperties detailProperties,
                                          SearchProperties searchProperties,
                                          ObjectMapper objectMapper) {
        this.detailProperties = detailProperties;
        this.searchProperties = searchProperties;
        this.objectMapper = objectMapper.copy();
        this.objectMapper.getFactory().setStreamReadConstraints(
            this.objectMapper.getFactory().streamReadConstraints().rebuild()
                .maxStringLength(detailProperties.getMaxStringLength())
                .build()
        );
    }

    @PostConstruct
    public void open() {
        if (!configured()) {
            return;
        }
        SearchProperties.OpenSearch config = openSearchConfig();
        if (blank(config.getUrl())) {
            throw new IllegalStateException("OpenSearch URL is required for external chat detail storage");
        }
        this.restClient = buildRestClient(config);
        try {
            ensureIndex();
            this.available = true;
            log.info("OpenSearch chat detail store ready url={} index={}", config.getUrl(), indexName());
        } catch (RuntimeException ex) {
            close();
            throw new IllegalStateException("Failed to initialize OpenSearch chat detail store", ex);
        }
    }

    @PreDestroy
    public void close() {
        available = false;
        if (restClient == null) {
            return;
        }
        try {
            restClient.close();
        } catch (IOException ex) {
            log.warn("Failed to close OpenSearch chat detail client error={}", ex.getMessage());
        } finally {
            restClient = null;
        }
    }

    @Override
    public boolean isEnabled() {
        return configured() && available;
    }

    @Override
    public void put(String documentId, ChatMessageDetail detail) {
        requireAvailable();
        var source = objectMapper.createObjectNode();
        source.put("tenantId", detail.getTenantId());
        source.put("sessionId", detail.getSessionId());
        source.put("messageId", detail.getMessageId());
        if (detail.getCreatedAt() != null) {
            source.put("createdAt", detail.getCreatedAt().toString());
        }
        source.set("detail", objectMapper.valueToTree(detail));
        request("PUT", "/" + indexName() + "/_doc/" + documentId, source, false);
    }

    @Override
    public Optional<ChatMessageDetail> get(String documentId) {
        requireAvailable();
        JsonNode source = request("GET", "/" + indexName() + "/_source/" + documentId, null, true);
        if (source == null || source.path("detail").isMissingNode()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.treeToValue(source.path("detail"), ChatMessageDetail.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to deserialize OpenSearch chat detail documentId=" + documentId, ex);
        }
    }

    @Override
    public Map<String, ChatMessageDetail> getAll(List<String> documentIds) {
        requireAvailable();
        List<String> ids = documentIds == null
            ? List.of()
            : documentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        JsonNode response = request(
            "POST",
            "/" + indexName() + "/_mget",
            Map.of("ids", ids),
            false
        );
        Map<String, ChatMessageDetail> details = new LinkedHashMap<>();
        JsonNode documents = response == null ? null : response.path("docs");
        if (documents == null || !documents.isArray()) {
            return details;
        }
        List<String> invalidIds = new ArrayList<>();
        for (JsonNode document : documents) {
            String documentId = document.path("_id").asText("");
            JsonNode detail = document.path("_source").path("detail");
            if (documentId.isBlank() || !document.path("found").asBoolean(false) || detail.isMissingNode()) {
                continue;
            }
            try {
                details.put(documentId, objectMapper.treeToValue(detail, ChatMessageDetail.class));
            } catch (IOException | RuntimeException ex) {
                invalidIds.add(documentId);
            }
        }
        if (!invalidIds.isEmpty()) {
            log.warn("Skipped invalid OpenSearch chat detail documents count={} ids={}",
                invalidIds.size(), invalidIds);
        }
        return details;
    }

    @Override
    public void putText(String documentId,
                        String kind,
                        String tenantId,
                        String sessionId,
                        String entityId,
                        String text) {
        requireAvailable();
        var source = objectMapper.createObjectNode();
        source.put("kind", kind);
        source.put("tenantId", tenantId);
        source.put("sessionId", sessionId);
        source.put("entityId", entityId);
        var payload = objectMapper.createObjectNode();
        payload.put("value", text);
        source.set("payload", payload);
        request("PUT", "/" + indexName() + "/_doc/" + documentId, source, false);
    }

    @Override
    public Optional<String> getText(String documentId) {
        requireAvailable();
        JsonNode source = request("GET", "/" + indexName() + "/_source/" + documentId, null, true);
        if (source == null || !source.path("payload").path("value").isTextual()) {
            return Optional.empty();
        }
        return Optional.of(source.path("payload").path("value").textValue());
    }

    @Override
    public void delete(String documentId) {
        if (!isEnabled()) {
            return;
        }
        request("DELETE", "/" + indexName() + "/_doc/" + documentId, null, true);
    }

    private void ensureIndex() {
        if (request("HEAD", "/" + indexName(), null, true) != null) {
            return;
        }
        Map<String, Object> mapping = Map.of(
            "mappings", Map.of(
                "dynamic", false,
                "properties", Map.of(
                    "tenantId", Map.of("type", "keyword"),
                    "sessionId", Map.of("type", "keyword"),
                    "messageId", Map.of("type", "keyword"),
                    "kind", Map.of("type", "keyword"),
                    "entityId", Map.of("type", "keyword"),
                    "createdAt", Map.of("type", "date"),
                    "detail", Map.of("type", "object", "enabled", false),
                    "payload", Map.of("type", "object", "enabled", false)
                )
            )
        );
        request("PUT", "/" + indexName(), mapping, false);
    }

    private JsonNode request(String method, String path, Object body, boolean allowNotFound) {
        try {
            Request request = new Request(method.toUpperCase(Locale.ROOT), path);
            if (body != null) {
                request.setEntity(new ByteArrayEntity(objectMapper.writeValueAsBytes(body), ContentType.APPLICATION_JSON));
            }
            Response response = restClient.performRequest(request);
            StatusLine status = response.getStatusLine();
            byte[] responseBody = entityBytes(response.getEntity());
            if (allowNotFound && status.getStatusCode() == 404) {
                return null;
            }
            if (status.getStatusCode() < 200 || status.getStatusCode() >= 300) {
                throw requestFailure(status.getStatusCode(), path, responseBody, null);
            }
            if (responseBody.length == 0 || "HEAD".equalsIgnoreCase(method)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (ResponseException ex) {
            Response response = ex.getResponse();
            int status = response == null ? 0 : response.getStatusLine().getStatusCode();
            if (allowNotFound && status == 404) {
                return null;
            }
            byte[] responseBody = response == null ? new byte[0] : entityBytesQuietly(response.getEntity());
            throw requestFailure(status, path, responseBody, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("OpenSearch chat detail request failed path=" + path, ex);
        }
    }

    private IllegalStateException requestFailure(int status, String path, byte[] body, Exception cause) {
        String responseBody = new String(body, StandardCharsets.UTF_8);
        String message = "OpenSearch chat detail request failed status=" + status
            + " path=" + path + " body=" + responseBody;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private RestClient buildRestClient(SearchProperties.OpenSearch config) {
        URI uri = URI.create(config.getUrl().trim());
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), port, uri.getScheme()))
            .setRequestConfigCallback(requestConfig -> requestConfig
                .setConnectTimeout(Math.max(1, config.getConnectTimeoutMs()))
                .setSocketTimeout(Math.max(1, config.getRequestTimeoutMs())));
        if (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
            builder.setPathPrefix(uri.getPath());
        }
        CredentialsProvider credentials = new BasicCredentialsProvider();
        if (!blank(config.getUsername()) || !blank(config.getPassword())) {
            credentials.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(nullToEmpty(config.getUsername()), nullToEmpty(config.getPassword())));
        }
        builder.setHttpClientConfigCallback(httpClient -> {
            httpClient.setDefaultCredentialsProvider(credentials);
            if (config.isInsecureSsl()) {
                httpClient.setSSLContext(insecureSslContext());
                httpClient.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            }
            return httpClient;
        });
        return builder.build();
    }

    private SSLContext insecureSslContext() {
        try {
            return SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to create insecure OpenSearch SSL context", ex);
        }
    }

    private String indexName() {
        String value = detailProperties.getExternalText().getIndexName();
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!INDEX_NAME.matcher(normalized).matches()) {
            throw new IllegalStateException("Invalid OpenSearch chat detail index name: " + value);
        }
        return normalized;
    }

    private SearchProperties.OpenSearch openSearchConfig() {
        return searchProperties.getOpenSearch() == null
            ? new SearchProperties.OpenSearch()
            : searchProperties.getOpenSearch();
    }

    private boolean configured() {
        return detailProperties.getExternalText() != null
            && detailProperties.getExternalText().isEnabled();
    }

    private void requireAvailable() {
        if (!isEnabled()) {
            throw new IllegalStateException("OpenSearch chat detail store is unavailable");
        }
    }

    private byte[] entityBytes(HttpEntity entity) throws IOException {
        return entity == null ? new byte[0] : EntityUtils.toByteArray(entity);
    }

    private byte[] entityBytesQuietly(HttpEntity entity) {
        try {
            return entityBytes(entity);
        } catch (IOException ex) {
            return ("Failed to read response: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
