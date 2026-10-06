package com.chatchat.knowledgebase.search.index.infrastructure.opensearch;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.opensearch.client.RestClient;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OpenSearchSemanticIndexSchemaTest {
    @Test
    void separatesModelsWithEqualDimensionsAndDoesNotIncludeCredentials() {
        var config = new SearchProperties.OpenSearch.Embedding();
        config.setEnabled(true);
        config.setModel("model-a");
        String first = OpenSearchSemanticIndexSchema.indexName("generic_index", config);
        config.setApiKey("rotated-secret");
        assertThat(OpenSearchSemanticIndexSchema.indexName("generic_index", config)).isEqualTo(first);
        config.setModel("model-b");
        assertThat(OpenSearchSemanticIndexSchema.indexName("generic_index", config)).isNotEqualTo(first);
        config.setModel("model-a");
        config.setDimension(2560);
        assertThat(OpenSearchSemanticIndexSchema.indexName("generic_index", config)).isNotEqualTo(first);
    }

    @Test
    void retriesInterruptedLexicalMigrationWithoutCopyingIncompatibleVectors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean created = new AtomicBoolean();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicInteger copies = new AtomicInteger();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = "{}";
            int status = 200;
            if ("HEAD".equals(method)) status = path.equals("/new") && !created.get() ? 404 : 200;
            else if (path.equals("/new")) { created.set(true); assertThat(body).contains("knn_vector", "2560"); }
            else if (path.equals("/new/_mapping") && method.equals("GET")) {
                response = "{\"new\":{\"mappings\":{\"properties\":{\"embedding\":{\"type\":\"knn_vector\",\"dimension\":2560}},\"_meta\":{\"lexicalMigrationComplete\":" + complete.get() + "}}}}";
            } else if (path.equals("/new/_mapping")) complete.set(true);
            else if (path.equals("/new/_settings")) response = "{\"new\":{\"settings\":{\"index\":{\"knn\":\"true\"}}}}";
            else if (path.equals("/_reindex")) {
                assertThat(body).contains("excludes", "embedding", "create");
                response = copies.incrementAndGet() == 1 ? "{\"failures\":[{}]}" : "{\"failures\":[]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, method.equals("HEAD") ? -1 : bytes.length);
            if (!method.equals("HEAD")) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (RestClient client = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var config = new SearchProperties.OpenSearch.Embedding();
            config.setEnabled(true); config.setDimension(2560);
            assertThatThrownBy(() -> OpenSearchSemanticIndexSchema.ensure(client, new ObjectMapper(), "old", "new",
                "embedding", config, Map.of("text", Map.of("type", "text")), true))
                .hasMessageContaining("migration incomplete");
            OpenSearchSemanticIndexSchema.ensure(client, new ObjectMapper(), "old", "new", "embedding", config, Map.of(), true);
            OpenSearchSemanticIndexSchema.ensure(client, new ObjectMapper(), "old", "new", "embedding", config, Map.of(), true);
            assertThat(copies.get()).isEqualTo(2);
            assertThat(created).isTrue();
            assertThat(complete).isTrue();
        } finally { server.stop(0); }
    }

    @Test
    void quarantinesIncompatibleExistingMappingWithoutDeletingData() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean rotated = new AtomicBoolean();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            exchange.getRequestBody().readAllBytes();
            String response = "{}";
            int status = 200;
            if (method.equals("DELETE")) deletes.incrementAndGet();
            if (method.equals("HEAD")) status = path.equals("/profile_r1") && !rotated.get() ? 404 : 200;
            else if (path.equals("/profile_r1") && method.equals("PUT")) { rotated.set(true); creations.incrementAndGet(); }
            else if (path.endsWith("/_mapping")) {
                String name = path.contains("_r1") ? "profile_r1" : "profile";
                String type = name.equals("profile") ? "float" : "knn_vector";
                response = "{\"" + name + "\":{\"mappings\":{\"properties\":{\"vector\":{\"type\":\"" + type + "\",\"dimension\":2560}}}}}";
            } else if (path.endsWith("/_settings")) {
                String name = path.contains("_r1") ? "profile_r1" : "profile";
                response = "{\"" + name + "\":{\"settings\":{\"index\":{\"knn\":\"true\"}}}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, method.equals("HEAD") ? -1 : bytes.length);
            if (!method.equals("HEAD")) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (RestClient client = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var config = new SearchProperties.OpenSearch.Embedding();
            config.setEnabled(true); config.setDimension(2560);
            for (int i = 0; i < 2; i++) {
                assertThat(OpenSearchSemanticIndexSchema.ensure(client, new ObjectMapper(), "legacy", "profile",
                    "vector", config, Map.of(), false)).isEqualTo("profile_r1");
            }
            assertThat(creations.get()).isEqualTo(1);
            assertThat(deletes.get()).isZero();
        } finally { server.stop(0); }
    }
}
