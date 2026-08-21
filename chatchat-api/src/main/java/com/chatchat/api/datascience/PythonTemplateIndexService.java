package com.chatchat.api.datascience;

import com.chatchat.knowledgebase.search.OpenSearchEmbeddingClient;
import com.chatchat.knowledgebase.search.SearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContexts;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.net.ssl.SSLContext;

@Slf4j
@Service
@RequiredArgsConstructor
public class PythonTemplateIndexService {
    private final PythonDataScienceProperties properties;
    private final SearchProperties searchProperties;
    private final OpenSearchEmbeddingClient embeddingClient;
    private final PythonTemplateRepository repository;
    private final ObjectMapper objectMapper;

    public IndexResult index(PythonTemplateEntity template) {
        if (!openSearchEnabled()) return new IndexResult(true, "LOCAL_ONLY", "OpenSearch 未启用，已保留数据库检索索引");
        try (RestClient client = client()) {
            ensureIndex(client);
            List<Float> vector = embeddingClient.embed(template.getSearchText());
            Map<String, Object> document = document(template);
            if (!vector.isEmpty()) document.put(vectorField(), vector);
            Request request = new Request("PUT", "/" + properties.getIndexName() + "/_doc/" + template.getId() + "?refresh=true");
            request.setJsonEntity(objectMapper.writeValueAsString(document));
            Response response = client.performRequest(request);
            boolean success = response.getStatusLine().getStatusCode() < 300;
            return new IndexResult(success, vector.isEmpty() ? "BM25" : "BM25_KNN", success ? "" : "索引写入失败");
        } catch (Exception ex) {
            log.warn("Python template indexing failed id={}: {}", template.getId(), ex.getMessage());
            return new IndexResult(false, "FAILED", ex.getMessage());
        }
    }

    public void remove(String templateId) {
        if (!openSearchEnabled()) return;
        try (RestClient client = client()) {
            client.performRequest(new Request("DELETE", "/" + properties.getIndexName() + "/_doc/" + templateId + "?refresh=true"));
        } catch (Exception ex) {
            log.warn("Python template index removal failed id={}: {}", templateId, ex.getMessage());
        }
    }

    public List<SearchHit> search(String tenantId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        if (!openSearchEnabled()) return lexicalFallback(tenantId, query, limit);
        try (RestClient client = client()) {
            Map<String, RankedHit> merged = new LinkedHashMap<>();
            merge(merged, searchBm25(client, tenantId, query, limit), "BM25");
            List<Float> vector = embeddingClient.embed(query);
            if (!vector.isEmpty()) merge(merged, searchKnn(client, tenantId, vector, limit), "KNN");
            return merged.values().stream().sorted(Comparator.comparingDouble(RankedHit::score).reversed()).limit(limit)
                    .map(hit -> new SearchHit(hit.id(), hit.name(), hit.scenario(), hit.score(), String.join("+", hit.channels()))).toList();
        } catch (Exception ex) {
            log.warn("Python template hybrid search failed: {}", ex.getMessage());
            return lexicalFallback(tenantId, query, limit);
        }
    }

    private List<JsonNode> searchBm25(RestClient client, String tenant, String query, int limit) throws Exception {
        Map<String, Object> body = Map.of("size", limit, "query", Map.of("bool", Map.of(
                "filter", List.of(Map.of("term", Map.of("tenantId", tenant)), Map.of("term", Map.of("status", "PUBLISHED"))),
                "must", List.of(Map.of("multi_match", Map.of("query", query, "fields", List.of("templateName^3", "scenario^2", "description", "keywords^2", "searchText")))))));
        return hits(client, body);
    }

    private List<JsonNode> searchKnn(RestClient client, String tenant, List<Float> vector, int limit) throws Exception {
        Map<String, Object> body = Map.of("size", limit, "query", Map.of("bool", Map.of(
                "filter", List.of(Map.of("term", Map.of("tenantId", tenant)), Map.of("term", Map.of("status", "PUBLISHED"))),
                "must", List.of(Map.of("knn", Map.of(vectorField(), Map.of("vector", vector, "k", limit)))))));
        return hits(client, body);
    }

    private List<JsonNode> hits(RestClient client, Map<String, Object> body) throws Exception {
        Request request = new Request("POST", "/" + properties.getIndexName() + "/_search");
        request.setJsonEntity(objectMapper.writeValueAsString(body));
        JsonNode root = objectMapper.readTree(client.performRequest(request).getEntity().getContent());
        List<JsonNode> hits = new ArrayList<>();
        root.path("hits").path("hits").forEach(hits::add);
        return hits;
    }

    private void merge(Map<String, RankedHit> merged, List<JsonNode> hits, String channel) {
        for (int rank = 0; rank < hits.size(); rank++) {
            JsonNode hit = hits.get(rank), source = hit.path("_source");
            String id = hit.path("_id").asText();
            double rrf = 1d / (60 + rank + 1);
            merged.compute(id, (key, current) -> current == null ? new RankedHit(id, source.path("templateName").asText(), source.path("scenario").asText(), rrf, new LinkedHashSet<>(List.of(channel))) : current.add(rrf, channel));
        }
    }

    private List<SearchHit> lexicalFallback(String tenant, String query, int limit) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        return repository.findByTenantIdOrderByPublishedAtDesc(tenant).stream().filter(t -> "PUBLISHED".equals(t.getStatus())).map(t -> {
            String text = t.getSearchText().toLowerCase(Locale.ROOT);
            double score = Arrays.stream(terms).filter(text::contains).count();
            return new SearchHit(t.getId(), t.getTemplateName(), t.getScenario(), score, "LOCAL_BM25_FALLBACK");
        }).filter(hit -> hit.score() > 0).sorted(Comparator.comparingDouble(SearchHit::score).reversed()).limit(limit).toList();
    }

    private Map<String, Object> document(PythonTemplateEntity t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("templateId", t.getId());
        map.put("tenantId", t.getTenantId());
        map.put("ownerId", t.getOwnerId());
        map.put("templateName", t.getTemplateName());
        map.put("scenario", t.getScenario());
        map.put("description", t.getDescription());
        map.put("keywords", t.getKeywords());
        map.put("domain", t.getDomain());
        map.put("assetId", t.getAssetId());
        map.put("scriptId", t.getScriptId());
        map.put("version", t.getVersion());
        map.put("status", "PUBLISHED");
        map.put("searchText", t.getSearchText());
        map.put("publishedAt", t.getPublishedAt().toString());
        return map;
    }

    private void ensureIndex(RestClient client) throws Exception {
        try {
            client.performRequest(new Request("HEAD", "/" + properties.getIndexName()));
            return;
        } catch (Exception ignored) {
        }
        int dimension = searchProperties.getOpenSearch().getEmbedding().getDimension();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String f : List.of("templateName", "scenario", "description", "keywords", "searchText"))
            fields.put(f, Map.of("type", "text"));
        for (String f : List.of("templateId", "tenantId", "ownerId", "assetId", "scriptId", "version", "status", "domain"))
            fields.put(f, Map.of("type", "keyword"));
        fields.put("publishedAt", Map.of("type", "date"));
        fields.put(vectorField(), Map.of("type", "knn_vector", "dimension", dimension, "method", Map.of("name", "hnsw", "space_type", "cosinesimil", "engine", "lucene")));
        Request create = new Request("PUT", "/" + properties.getIndexName());
        create.setJsonEntity(objectMapper.writeValueAsString(Map.of("settings", Map.of("index.knn", true), "mappings", Map.of("properties", fields))));
        client.performRequest(create);
    }

    private RestClient client() {
        SearchProperties.OpenSearch c = searchProperties.getOpenSearch();
        RestClientBuilder builder = RestClient.builder(HttpHost.create(c.getUrl()));
        if (c.getUsername() != null && !c.getUsername().isBlank()) {
            String auth = Base64.getEncoder().encodeToString((c.getUsername() + ":" + c.getPassword()).getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + auth)});
        }
        if (c.isInsecureSsl()) {
            builder.setHttpClientConfigCallback(httpClient -> httpClient.setSSLContext(insecureSslContext()).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE));
        }
        return builder.build();
    }

    private SSLContext insecureSslContext() {
        try {
            return SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to create insecure OpenSearch SSL context", ex);
        }
    }

    private boolean openSearchEnabled() {
        SearchProperties.OpenSearch c = searchProperties.getOpenSearch();
        return searchProperties.isOpenSearchEngine() && c.isEnabled() && c.getUrl() != null && !c.getUrl().isBlank();
    }

    private String vectorField() {
        return "embedding";
    }

    public record IndexResult(boolean success, String mode, String message) {
    }

    public record SearchHit(String templateId, String templateName, String scenario, double score, String channel) {
    }

    private record RankedHit(String id, String name, String scenario, double score, LinkedHashSet<String> channels) {
        RankedHit add(double delta, String channel) {
            channels.add(channel);
            return new RankedHit(id, name, scenario, score + delta, channels);
        }
    }
}
