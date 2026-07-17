package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.ssl.SSLContexts;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.security.GeneralSecurityException;

@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.open-search", name = "enabled", havingValue = "true")
public class OpenSearchNewsDocumentStore implements NewsDocumentStore {

    private final NewsRuntimeProperties.OpenSearch properties;
    private final NewsIndexNamingStrategy indexNaming;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final NewsEmbeddingClient embeddingClient;
    private final Set<String> vectorReadyIndices = ConcurrentHashMap.newKeySet();
    private final Set<String> checkedVectorIndices = ConcurrentHashMap.newKeySet();

    public OpenSearchNewsDocumentStore(NewsRuntimeProperties runtimeProperties, ObjectMapper objectMapper,
                                       NewsIndexNamingStrategy indexNaming, NewsEmbeddingClient embeddingClient) {
        this.properties = runtimeProperties.getOpenSearch();
        this.objectMapper = objectMapper;
        this.indexNaming = indexNaming;
        this.embeddingClient = embeddingClient;
        java.net.URI endpoint = java.net.URI.create(properties.getEndpoint());
        var builder = RestClient.builder(new HttpHost(endpoint.getHost(),
            endpoint.getPort() < 0 ? ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80) : endpoint.getPort(),
            endpoint.getScheme()));
        BasicCredentialsProvider credentials = new BasicCredentialsProvider();
        boolean hasCredentials = properties.getUsername() != null && !properties.getUsername().isBlank();
        if (hasCredentials) {
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                properties.getUsername(), properties.getPassword() == null ? "" : properties.getPassword()));
        }
        builder.setHttpClientConfigCallback(http -> {
            if (hasCredentials) http.setDefaultCredentialsProvider(credentials);
            if (properties.isInsecureSsl()) {
                try {
                    http.setSSLContext(SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build());
                    http.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                } catch (GeneralSecurityException ex) {
                    throw new IllegalStateException("Failed to configure insecure News OpenSearch SSL", ex);
                }
            }
            return http;
        });
        this.client = builder.build();
    }

    @Override
    public void bulkIndex(List<NewsDocument> documents) throws Exception {
        if (documents.isEmpty()) return;
        StringBuilder body = new StringBuilder();
        Map<String, List<NewsDocument>> byIndex = new LinkedHashMap<>();
        for (NewsDocument document : documents) {
            byIndex.computeIfAbsent(indexNaming.writeIndex(document.collectTime()), ignored -> new ArrayList<>()).add(document);
        }
        for (Map.Entry<String, List<NewsDocument>> entry : byIndex.entrySet()) {
            boolean vectorReady = embeddingClient.enabled() && ensureVectorIndex(entry.getKey());
            List<List<Float>> vectors = vectorReady ? embed(entry.getValue()) : List.of();
            for (int i = 0; i < entry.getValue().size(); i++) {
                NewsDocument document = entry.getValue().get(i);
                body.append(objectMapper.writeValueAsString(MapFactory.indexAction(entry.getKey(), document.documentId())))
                    .append('\n');
                ObjectNode source = objectMapper.valueToTree(document);
                if (vectors.size() == entry.getValue().size() && !vectors.get(i).isEmpty()) {
                    source.set(vectorField(), objectMapper.valueToTree(vectors.get(i)));
                }
                body.append(objectMapper.writeValueAsString(source)).append('\n');
            }
        }
        Request request = new Request("POST", "/_bulk");
        request.setJsonEntity(body.toString());
        JsonNode response = read(client.performRequest(request));
        if (response.path("errors").asBoolean(false)) {
            throw new IllegalStateException("OpenSearch Bulk returned item failures: " + bulkErrors(response));
        }
    }

    @Override
    public List<NewsDocument> search(NewsSearchQuery query) throws Exception {
        int requested = Math.max(1, Math.min(query.size(), 50));
        boolean semantic = query.query() != null && !query.query().isBlank() && embeddingClient.enabled();
        int candidateLimit = semantic ? Math.max(requested, properties.getEmbedding().getVectorCandidateLimit()) : requested;
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", candidateLimit);
        ArrayNode must = root.putObject("query").putObject("bool").putArray("must");
        if (query.query() != null && !query.query().isBlank()) {
            must.addObject().putObject("multi_match").put("query", query.query())
                .putArray("fields").add("title^3").add("summary^2").add("content").add("tags").add("categories");
        } else {
            must.addObject().putObject("match_all");
        }
        ArrayNode filters = ((ObjectNode) root.path("query").path("bool")).putArray("filter");
        appendFilters(filters, query);
        if (query.query() == null || query.query().isBlank()) root.putArray("sort").addObject().put("publishTime", "desc");
        String targets = String.join(",", indexNaming.readableIndices());
        List<SearchHit> lexical;
        try {
            lexical = hits(searchRequest(targets, root));
        } catch (ResponseException ex) {
            if (ex.getResponse() != null && ex.getResponse().getStatusLine().getStatusCode() == 404) return List.of();
            throw ex;
        }
        if (!semantic) return documents(lexical, requested);
        List<Float> queryVector = embeddingClient.embed(query.query());
        if (queryVector.isEmpty()) return documents(lexical, requested);
        List<SearchHit> vector = List.of();
        try {
            String vectorTargets = indexNaming.readableIndices().stream().filter(this::hasVectorIndex)
                .collect(java.util.stream.Collectors.joining(","));
            if (vectorTargets.isBlank()) return documents(lexical, requested);
            ObjectNode vectorRoot = objectMapper.createObjectNode();
            vectorRoot.put("size", candidateLimit);
            ObjectNode field = vectorRoot.putObject("query").putObject("knn").putObject(vectorField());
            field.set("vector", objectMapper.valueToTree(queryVector));
            field.put("k", candidateLimit);
            ObjectNode filter = objectMapper.createObjectNode();
            ArrayNode vectorFilters = filter.putObject("bool").putArray("filter");
            appendFilters(vectorFilters, query);
            if (!vectorFilters.isEmpty()) field.set("filter", filter);
            vector = hits(searchRequest(vectorTargets, vectorRoot));
        } catch (Exception ex) {
            // Older daily indices may not have a vector mapping; lexical recall must remain available.
        }
        return documents(rrf(lexical, vector), requested);
    }

    private JsonNode searchRequest(String targets, ObjectNode body) throws Exception {
        Request request = new Request("POST", "/" + targets + "/_search");
        request.addParameter("ignore_unavailable", "true");
        request.addParameter("allow_no_indices", "true");
        request.setJsonEntity(objectMapper.writeValueAsString(body));
        return read(client.performRequest(request));
    }

    private void appendFilters(ArrayNode filters, NewsSearchQuery query) {
        if (query.sourceIds() != null && !query.sourceIds().isEmpty()) {
            filters.addObject().putObject("terms").set("sourceId", objectMapper.valueToTree(query.sourceIds()));
        }
        if (query.categories() != null && !query.categories().isEmpty()) {
            filters.addObject().putObject("terms").set("categories", objectMapper.valueToTree(query.categories()));
        }
        if (query.startTime() != null || query.endTime() != null) {
            ObjectNode range = filters.addObject().putObject("range").putObject("publishTime");
            if (query.startTime() != null) range.put("gte", query.startTime().toString());
            if (query.endTime() != null) range.put("lte", query.endTime().toString());
        }
    }

    private List<SearchHit> hits(JsonNode response) {
        List<SearchHit> values = new ArrayList<>();
        int rank = 0;
        for (JsonNode hit : response.path("hits").path("hits")) {
            values.add(new SearchHit(hit.path("_id").asText(), hit.path("_source").deepCopy(), rank++));
        }
        return values;
    }

    private List<SearchHit> rrf(List<SearchHit> lexical, List<SearchHit> vector) {
        int constant = Math.max(1, properties.getEmbedding().getRrfRankConstant());
        Map<String, RankedHit> merged = new LinkedHashMap<>();
        addRanks(merged, lexical, constant);
        addRanks(merged, vector, constant);
        return merged.values().stream().sorted(Comparator.comparingDouble(RankedHit::score).reversed())
            .map(RankedHit::hit).toList();
    }

    private void addRanks(Map<String, RankedHit> merged, List<SearchHit> hits, int constant) {
        for (SearchHit hit : hits) {
            double score = 1.0D / (constant + hit.rank() + 1.0D);
            merged.compute(hit.id(), (id, existing) -> existing == null
                ? new RankedHit(hit, score) : new RankedHit(existing.hit(), existing.score() + score));
        }
    }

    private List<NewsDocument> documents(List<SearchHit> hits, int limit) throws Exception {
        List<NewsDocument> documents = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (documents.size() >= limit) break;
            if (hit.source() instanceof ObjectNode object) object.remove(vectorField());
            documents.add(objectMapper.treeToValue(hit.source(), NewsDocument.class));
        }
        return documents;
    }

    private List<List<Float>> embed(List<NewsDocument> documents) {
        int batchSize = Math.max(1, properties.getEmbedding().getBatchSize());
        List<List<Float>> result = new ArrayList<>();
        for (int start = 0; start < documents.size(); start += batchSize) {
            int end = Math.min(documents.size(), start + batchSize);
            List<String> inputs = documents.subList(start, end).stream()
                .map(document -> document.title() + "\n" + document.content()).toList();
            List<List<Float>> batch = embeddingClient.embedAll(inputs);
            if (batch.size() != inputs.size()) return List.of();
            result.addAll(batch);
        }
        return result;
    }

    private boolean ensureVectorIndex(String index) {
        if (vectorReadyIndices.contains(index)) return true;
        try {
            ObjectNode create = objectMapper.createObjectNode();
            create.putObject("settings").putObject("index").put("knn", true);
            create.putObject("mappings").putObject("properties").set(vectorField(), vectorMapping());
            Request request = new Request("PUT", "/" + index);
            request.setJsonEntity(objectMapper.writeValueAsString(create));
            try { client.performRequest(request); }
            catch (ResponseException ex) {
                if (ex.getResponse() == null || ex.getResponse().getStatusLine().getStatusCode() != 400) throw ex;
                Request settings = new Request("PUT", "/" + index + "/_settings");
                settings.setJsonEntity("{\"index.knn\":true}");
                client.performRequest(settings);
                Request mapping = new Request("PUT", "/" + index + "/_mapping");
                mapping.setJsonEntity(objectMapper.writeValueAsString(Map.of("properties", Map.of(vectorField(), vectorMapping()))));
                client.performRequest(mapping);
            }
            vectorReadyIndices.add(index);
            checkedVectorIndices.add(index);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean hasVectorIndex(String index) {
        if (vectorReadyIndices.contains(index)) return true;
        if (!checkedVectorIndices.add(index)) return false;
        try {
            JsonNode mapping = read(client.performRequest(new Request("GET", "/" + index + "/_mapping")));
            JsonNode field = mapping.path(index).path("mappings").path("properties").path(vectorField());
            if ("knn_vector".equals(field.path("type").asText())
                && field.path("dimension").asInt() == properties.getEmbedding().getDimension()) {
                vectorReadyIndices.add(index);
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private ObjectNode vectorMapping() {
        return objectMapper.createObjectNode().put("type", "knn_vector")
            .put("dimension", Math.max(1, properties.getEmbedding().getDimension()));
    }

    private String vectorField() {
        String field = properties.getEmbedding().getVectorField();
        return field == null || field.isBlank() ? "embedding" : field.trim();
    }

    public List<String> deleteExpiredIndices() throws Exception {
        Request listRequest = new Request("GET", "/_cat/indices/" + indexNaming.indexPattern());
        listRequest.addParameter("format", "json");
        listRequest.addParameter("h", "index");
        listRequest.addParameter("expand_wildcards", "open,closed");
        JsonNode indices;
        try {
            indices = read(client.performRequest(listRequest));
        } catch (ResponseException ex) {
            if (ex.getResponse() != null && ex.getResponse().getStatusLine().getStatusCode() == 404) return List.of();
            throw ex;
        }
        List<String> deleted = new ArrayList<>();
        for (JsonNode item : indices) {
            String index = item.path("index").asText();
            if (!indexNaming.isExpired(index)) continue;
            try {
                client.performRequest(new Request("DELETE", "/" + index));
                deleted.add(index);
            } catch (ResponseException ex) {
                if (ex.getResponse() == null || ex.getResponse().getStatusLine().getStatusCode() != 404) throw ex;
            }
        }
        return deleted;
    }

    private JsonNode read(Response response) throws Exception {
        try (InputStream input = response.getEntity().getContent()) {
            return objectMapper.readTree(input);
        }
    }

    private String bulkErrors(JsonNode response) {
        List<String> errors = new ArrayList<>();
        for (JsonNode item : response.path("items")) {
            JsonNode error = item.path("index").path("error");
            if (!error.isMissingNode()) errors.add(error.toString());
            if (errors.size() >= 3) break;
        }
        return String.join("; ", errors);
    }

    @PreDestroy
    public void close() throws Exception {
        client.close();
    }

    private static final class MapFactory {
        private MapFactory() { }

        static java.util.Map<String, Object> indexAction(String index, String id) {
            return java.util.Map.of("index", java.util.Map.of("_index", index, "_id", id));
        }
    }

    private record SearchHit(String id, JsonNode source, int rank) { }
    private record RankedHit(SearchHit hit, double score) { }
}
