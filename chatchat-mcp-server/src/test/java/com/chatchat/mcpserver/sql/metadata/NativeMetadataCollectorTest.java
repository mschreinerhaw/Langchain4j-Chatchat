package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.datacapability.connection.QueryHttpClient;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeMetadataCollectorTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest @ValueSource(strings = {"opensearch", "elasticsearch"})
    void collectsMappingsCapabilitiesAliasesAndVectorDefinitionsWithAssetCredentials(String type) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new ArrayList<>(), auth = new ArrayList<>();
        String vectorType = type.equals("opensearch") ? "knn_vector" : "dense_vector";
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = exchange.getRequestURI().getPath().endsWith("/_mapping") ? """
                {"news-2026":{"mappings":{"_meta":{"description":"资讯"},"properties":{
                "title":{"type":"text","fields":{"keyword":{"type":"keyword"}}},
                "author":{"type":"nested","properties":{"name":{"type":"keyword"}}},
                "embedding":{"type":"VECTOR_TYPE","dimension":384,"dims":384}}}}}
                """.replace("VECTOR_TYPE", vectorType)
                : exchange.getRequestURI().getPath().endsWith("/_alias") ? "{\"news-2026\":{\"aliases\":{\"news\":{}}}}"
                : "{\"fields\":{\"title\":{\"text\":{\"searchable\":true,\"aggregatable\":false}},\"title.keyword\":{\"keyword\":{\"searchable\":true,\"aggregatable\":true}}}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var asset = asset(type, "http://127.0.0.1:" + server.getAddress().getPort());
            var objects = new SearchEngineMetadataCollector(new QueryHttpClient(json)).collect(asset, List.of("news"));
            assertThat(objects).hasSize(1);
            var object = objects.get(0);
            assertThat(object.databaseType()).isEqualTo(type);
            assertThat(object.name()).isEqualTo("news-2026");
            assertThat(object.attributes().get("aliases")).isEqualTo(List.of("news"));
            assertThat(object.fields()).extracting(MetadataObject.Field::name).containsExactly("author", "author.name", "embedding", "title", "title.keyword");
            assertThat(object.fields().stream().filter(field -> field.name().equals("title.keyword")).findFirst().orElseThrow().attributes()).containsEntry("aggregatable", true);
            assertThat(object.fields().stream().filter(field -> field.name().equals("embedding")).findFirst().orElseThrow().nativeType()).isEqualTo(vectorType);
            assertThat(requests).containsExactly("GET /news/_mapping", "GET /news/_alias", "GET /news-2026/_field_caps");
            assertThat(auth).allMatch(value -> value.equals("Basic cmVhZGVyOnNlY3JldA=="));
        } finally { server.stop(0); }
    }

    @Test void emptyOrInvalidSearchScopesNeverContactTheEndpoint() {
        var http = mock(QueryHttpClient.class);
        var collector = new SearchEngineMetadataCollector(http);
        var asset = asset("opensearch", "http://localhost:9200");
        assertThatThrownBy(() -> collector.collect(asset, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collector.collect(asset, List.of("../_search"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }

    @Test void incompleteFieldCapabilitiesNeverProduceASuccessfulPartialSnapshot() throws Exception {
        var http = mock(QueryHttpClient.class);
        when(http.get(any(), contains("/_mapping"), anyInt())).thenReturn(json.readTree("{\"news\":{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}}"));
        when(http.get(any(), contains("/_alias"), anyInt())).thenReturn(json.readTree("{\"news\":{\"aliases\":{}}}"));
        when(http.get(any(), contains("/_field_caps"), anyInt())).thenReturn(json.readTree("{}"));
        assertThatThrownBy(() -> new SearchEngineMetadataCollector(http).collect(asset("elasticsearch", "http://localhost:9200"), List.of("news")))
            .hasMessageContaining("返回不完整");
    }

    @Test void keepsGraphDeclarationsSeparateFromObservedTypesAndStatistics() throws Exception {
        var http = mock(QueryHttpClient.class);
        var results = json.createArrayNode();
        results.add(result(List.of("label"), List.of(List.of("Person"), List.of("Company"))));
        results.add(result(List.of("relationshipType"), List.of(List.of("WORKS_AT"))));
        results.add(result(List.of("nodeType", "nodeLabels", "propertyName", "propertyTypes", "mandatory"),
            List.of(List.of(":Person", List.of("Person"), "name", List.of("String"), true))));
        results.add(result(List.of("relType", "propertyName", "propertyTypes", "mandatory"),
            List.of(List.of(":`WORKS_AT`", "since", List.of("Long"), false))));
        results.add(result(List.of("name", "entityType", "labelsOrTypes", "properties", "readCount"),
            List.of(List.of("person_name", "NODE", List.of("Person"), List.of("name"), 42))));
        results.add(result(List.of("name", "entityType", "labelsOrTypes", "properties", "type"),
            List.of(List.of("unique_person", "NODE", List.of("Person"), List.of("name"), "UNIQUENESS"))));
        results.add(json.readTree("""
            {"columns":[],"data":[{"row":[],"graph":{"nodes":[{"id":"1","labels":["Person"]},{"id":"2","labels":["Company"]}],
            "relationships":[{"id":"3","type":"WORKS_AT","startNode":"1","endNode":"2"}]}}]}
            """));
        var response = json.createObjectNode(); response.set("results", results); response.putArray("errors");
        when(http.post(any(), eq("/db/analytics/tx/commit"), any(), anyInt(), anyMap())).thenReturn(response);
        var objects = new Neo4jMetadataCollector(http).collect(asset("neo4j", "http://localhost:7474"), List.of("analytics"));
        assertThat(objects).hasSize(3);
        var person = objects.stream().filter(object -> object.name().equals("Person")).findFirst().orElseThrow();
        assertThat(person.fields().get(0).nullable()).isNull();
        assertThat(person.fields().get(0).attributes()).containsEntry("source", "observed").containsEntry("mandatory", true);
        assertThat(person.attributes().get("constraints").toString()).contains("UNIQUENESS");
        assertThat(person.attributes().get("indexes").toString()).doesNotContain("readCount");
        var relation = objects.stream().filter(object -> object.kind().equals("RELATIONSHIP_TYPE")).findFirst().orElseThrow();
        assertThat(relation.attributes().get("topology").toString()).contains("schema_statistics", "mayIncludeUnobservedRelationships=true");
    }

    @Test void graphErrorsAndIncompleteResultsFailTheRefresh() throws Exception {
        var http = mock(QueryHttpClient.class);
        var collector = new Neo4jMetadataCollector(http);
        when(http.post(any(), anyString(), any(), anyInt(), anyMap())).thenReturn(json.readTree("{\"errors\":[{\"code\":\"Forbidden\"}],\"results\":[]}"));
        assertThatThrownBy(() -> collector.collect(asset("neo4j", "http://localhost:7474"), List.of("neo4j"))).hasMessageContaining("采集失败");
        when(http.post(any(), anyString(), any(), anyInt(), anyMap())).thenReturn(json.readTree("{\"errors\":[],\"results\":[]}"));
        assertThatThrownBy(() -> collector.collect(asset("neo4j", "http://localhost:7474"), List.of("neo4j"))).hasMessageContaining("不完整");
    }

    private com.fasterxml.jackson.databind.JsonNode result(List<String> columns, List<List<Object>> rows) {
        return json.valueToTree(Map.of("columns", columns, "data", rows.stream().map(row -> Map.of("row", row)).toList()));
    }

    static SqlDatasourceConfig asset(String type, String address) {
        var asset = new SqlDatasourceConfig(); asset.setId("asset-" + type); asset.setName(type); asset.setDatabaseType(type);
        asset.setJdbcUrl(address); asset.setDriverClass(type + "-http"); asset.setUsername("reader"); asset.setPassword("secret");
        asset.setEnvironment("DEV"); asset.setToolName("db_query_" + type); asset.setEnabled(true);
        return asset;
    }
}
