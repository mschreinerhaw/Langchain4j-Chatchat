package com.chatchat.knowledgebase.search.index.infrastructure.opensearch;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.Request;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Embeddings from different spaces must never share an index, even at the same dimension. */
public final class OpenSearchSemanticIndexSchema {
    private OpenSearchSemanticIndexSchema() { }

    public static String indexName(String base, SearchProperties.OpenSearch.Embedding config) {
        String normalized = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (!config.isEnabled()) return normalized;
        String profile = "semantic.v1|" + config.getEndpoint() + "|" + config.getModel() + "|" + config.getDimension();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(profile.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return normalized.substring(0, Math.min(220, normalized.length())) + "_sem_" + hash;
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /** Creates a new embedding space and copies only lexical evidence from its legacy index. */
    public static String ensure(RestClient client, ObjectMapper mapper, String base, String index,
                              String vectorField, SearchProperties.OpenSearch.Embedding config,
                              Map<String, Object> lexicalFields, boolean migrateLexical) throws Exception {
        return ensureRevision(client, mapper, base, index, index, vectorField, config, lexicalFields, migrateLexical, 0);
    }

    private static String ensureRevision(RestClient client, ObjectMapper mapper, String base, String profile,
                              String index, String vectorField, SearchProperties.OpenSearch.Embedding config,
                              Map<String, Object> lexicalFields, boolean migrateLexical, int revision) throws Exception {
        boolean present = exists(client, index);
        if (present && config.isEnabled() && config.getDimension() > 0) {
            var mapping = mapper.readTree(client.performRequest(new Request("GET", "/" + index + "/_mapping"))
                .getEntity().getContent()).path(index).path("mappings").path("properties").path(vectorField);
            var settings = mapper.readTree(client.performRequest(new Request("GET", "/" + index + "/_settings"))
                .getEntity().getContent()).path(index).path("settings").path("index");
            if (!"knn_vector".equals(mapping.path("type").asText())
                || mapping.path("dimension").asInt() != config.getDimension()
                || !Boolean.parseBoolean(settings.path("knn").asText())) {
                if (revision >= 8) throw new IllegalStateException("No compatible semantic index schema available");
                return ensureRevision(client, mapper, base, profile, profile + "_r" + (revision + 1),
                    vectorField, config, lexicalFields, migrateLexical, revision + 1);
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>(lexicalFields);
        Map<String, Object> body = new LinkedHashMap<>();
        if (config.isEnabled() && config.getDimension() > 0) {
            fields.put(vectorField, Map.of("type", "knn_vector", "dimension", config.getDimension(),
                "method", Map.of("name", "hnsw", "space_type", "cosinesimil", "engine", "lucene")));
            body.put("settings", Map.of("index.knn", true));
        }
        body.put("mappings", Map.of("properties", fields));
        Request create = new Request("PUT", "/" + index);
        create.setJsonEntity(mapper.writeValueAsString(body));
        if (!present) {
            try {
                client.performRequest(create);
            } catch (ResponseException failure) {
                if (failure.getResponse().getStatusLine().getStatusCode() != 400 || !exists(client, index)) throw failure;
                return ensureRevision(client, mapper, base, profile, index, vectorField, config,
                    lexicalFields, migrateLexical, revision);
            }
        }
        if (!migrateLexical || base.equals(index)) return index;
        var mapping = mapper.readTree(client.performRequest(new Request("GET", "/" + index + "/_mapping"))
            .getEntity().getContent());
        if (mapping.path(index).path("mappings").path("_meta").path("lexicalMigrationComplete").asBoolean()) return index;
        if (exists(client, base)) {
            Request copy = new Request("POST", "/_reindex");
            copy.addParameter("refresh", "true");
            copy.setJsonEntity(mapper.writeValueAsString(Map.of(
                "source", Map.of("index", base, "_source", Map.of("excludes", java.util.List.of(vectorField))),
                "dest", Map.of("index", index, "op_type", "create"), "conflicts", "proceed")));
            var result = mapper.readTree(client.performRequest(copy).getEntity().getContent());
            if (result.path("failures").size() > 0 || result.path("timed_out").asBoolean()) {
                throw new IllegalStateException("Semantic index lexical migration incomplete");
            }
        }
        Request complete = new Request("PUT", "/" + index + "/_mapping");
        complete.setJsonEntity("{\"_meta\":{\"lexicalMigrationComplete\":true,\"semanticSchemaVersion\":1}}");
        client.performRequest(complete);
        return index;
    }

    private static boolean exists(RestClient client, String index) throws Exception {
        try {
            // RestClient treats HEAD 404 as an ordinary response, unlike GET/PUT 404.
            return client.performRequest(new Request("HEAD", "/" + index))
                .getStatusLine().getStatusCode() != 404;
        } catch (ResponseException failure) {
            if (failure.getResponse().getStatusLine().getStatusCode() == 404) return false;
            throw failure;
        }
    }
}
