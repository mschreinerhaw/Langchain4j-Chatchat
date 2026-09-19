package com.chatchat.api.datascience.skill;

import com.chatchat.api.datascience.python.PythonDataScienceProperties;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.OpenSearchEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContexts;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.ResponseException;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomainSkillIndexService {
    private final PythonDataScienceProperties properties;
    private final SearchProperties searchProperties;
    private final OpenSearchEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    public IndexResult index(DomainSkillEntity skill) {
        if (!openSearchEnabled()) return new IndexResult(true, "LOCAL_ONLY", "OpenSearch disabled; database search remains available");
        try (RestClient client = client()) {
            ensureIndex(client);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("skillId", skill.getId());
            document.put("tenantId", skill.getTenantId());
            document.put("ownerId", skill.getOwnerId());
            document.put("name", skill.getName());
            document.put("category", skill.getCategory());
            document.put("description", skill.getDescription());
            document.put("markdownContent", skill.getMarkdownContent());
            document.put("status", "PUBLISHED");
            document.put("publishedAt", skill.getPublishedAt().toString());
            String searchText = String.join("\n", safe(skill.getName()), safe(skill.getCategory()), safe(skill.getDescription()), safe(skill.getMarkdownContent()));
            document.put("searchText", searchText);
            List<Float> vector = embeddingClient.embed(searchText);
            if (!vector.isEmpty()) document.put("embedding", vector);
            Request request = new Request("PUT", "/" + properties.getDomainSkillIndexName() + "/_doc/" + skill.getId() + "?refresh=true");
            request.setJsonEntity(objectMapper.writeValueAsString(document));
            int status = client.performRequest(request).getStatusLine().getStatusCode();
            return new IndexResult(status < 300, vector.isEmpty() ? "BM25" : "BM25_KNN", status < 300 ? "" : "index write failed");
        } catch (Exception ex) {
            log.warn("Domain skill indexing failed id={}: {}", skill.getId(), ex.getMessage());
            return new IndexResult(false, "FAILED", ex.getMessage());
        }
    }

    public IndexResult remove(String skillId) {
        if (!openSearchEnabled()) return new IndexResult(true, "LOCAL_ONLY", "");
        try (RestClient client = client()) {
            int status = client.performRequest(new Request("DELETE", "/" + properties.getDomainSkillIndexName() + "/_doc/" + skillId + "?refresh=true"))
                .getStatusLine().getStatusCode();
            return new IndexResult(status < 300, "DELETE", status < 300 ? "" : "index removal failed");
        } catch (ResponseException ex) {
            if (ex.getResponse().getStatusLine().getStatusCode() == 404) return new IndexResult(true, "NOT_FOUND", "");
            log.warn("Domain skill index removal failed id={}: {}", skillId, ex.getMessage());
            return new IndexResult(false, "FAILED", ex.getMessage());
        } catch (Exception ex) {
            log.warn("Domain skill index removal failed id={}: {}", skillId, ex.getMessage());
            return new IndexResult(false, "FAILED", ex.getMessage());
        }
    }

    private void ensureIndex(RestClient client) throws Exception {
        try {
            client.performRequest(new Request("HEAD", "/" + properties.getDomainSkillIndexName()));
            return;
        } catch (Exception ignored) { }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : List.of("name", "description", "markdownContent", "searchText")) fields.put(field, Map.of("type", "text"));
        for (String field : List.of("skillId", "tenantId", "ownerId", "category", "status")) fields.put(field, Map.of("type", "keyword"));
        fields.put("publishedAt", Map.of("type", "date"));
        fields.put("embedding", Map.of("type", "knn_vector",
            "dimension", searchProperties.getOpenSearch().getEmbedding().getDimension(),
            "method", Map.of("name", "hnsw", "space_type", "cosinesimil", "engine", "lucene")));
        Request request = new Request("PUT", "/" + properties.getDomainSkillIndexName());
        request.setJsonEntity(objectMapper.writeValueAsString(Map.of("settings", Map.of("index.knn", true), "mappings", Map.of("properties", fields))));
        client.performRequest(request);
    }

    private boolean openSearchEnabled() {
        SearchProperties.OpenSearch c = searchProperties.getOpenSearch();
        return searchProperties.isOpenSearchEngine() && c.isEnabled() && c.getUrl() != null && !c.getUrl().isBlank();
    }

    private String safe(String value) { return value == null ? "" : value; }

    private RestClient client() {
        SearchProperties.OpenSearch c = searchProperties.getOpenSearch();
        RestClientBuilder builder = RestClient.builder(HttpHost.create(c.getUrl()));
        if (c.getUsername() != null && !c.getUsername().isBlank()) {
            String auth = Base64.getEncoder().encodeToString((c.getUsername() + ":" + c.getPassword()).getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + auth)});
        }
        if (c.isInsecureSsl()) builder.setHttpClientConfigCallback(http -> http.setSSLContext(insecureSslContext()).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE));
        return builder.build();
    }

    private SSLContext insecureSslContext() {
        try {
            return SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to create insecure OpenSearch SSL context", ex);
        }
    }

    public record IndexResult(boolean success, String mode, String message) { }
}
