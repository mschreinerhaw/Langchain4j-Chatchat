package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.connection.*;
import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.graph.GraphQueryAdapter;
import com.chatchat.mcpserver.datacapability.unstructured.OpenSearchQueryAdapter;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QueryHttpAdaptersTest {
    private final ObjectMapper json = new ObjectMapper();
    private final QueryConnectionService connections = mock(QueryConnectionService.class);
    private final QueryHttpClient http = new QueryHttpClient(json);
    private HttpServer server;
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpEndpointConfig connection;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        connection = new HttpEndpointConfig(); connection.setId("test_connection"); connection.setEnabled(true);
        connection.setUrlTemplate("http://127.0.0.1:" + server.getAddress().getPort());
        when(connections.get(any(), any(), anyBoolean())).thenReturn(connection);
    }
    @AfterEach void stop() { server.stop(0); }
    private CapabilityDefinition definition(CapabilityType type, String query, Map<String, Object> options) {
        return new CapabilityDefinition("test_query", "测试", null, type, null, connection.getId(), query, null, null, options, 5, 1, true, true, true);
    }
    private void response(String path, String body) {
        server.createContext(path, exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(json.readTree(exchange.getRequestBody()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
    }
    @Test void graphUsesSeparateCypherParametersAndReturnsGraphStructure() throws Exception {
        response("/db/neo4j/tx/commit", "{\"errors\":[],\"results\":[{\"columns\":[\"name\"],\"data\":[{\"row\":[\"Alice\"],\"graph\":{\"nodes\":[],\"relationships\":[]}}]}]}");
        var adapter = new GraphQueryAdapter(connections, http, json);
        var d = definition(CapabilityType.GRAPH, "MATCH (n) WHERE n.name = $name RETURN n.name AS name", Map.of("database", "neo4j"));
        adapter.validate(d);
        var result = adapter.execute(d, Map.of("name", "Alice' DELETE n"));
        assertThat(result.rows().get(0)).containsEntry("name", "Alice").containsKey("_graph");
        assertThat(request.get().at("/statements/0/parameters/name").asText()).isEqualTo("Alice' DELETE n");
        assertThat(request.get().at("/statements/0/statement").asText()).endsWith("RETURN * LIMIT 2");
    }
    @Test void graphRejectsWriteClausesAndBackendErrors() {
        var adapter = new GraphQueryAdapter(connections, http, json);
        assertThatThrownBy(() -> adapter.validate(definition(CapabilityType.GRAPH, "MATCH (n) DELETE n RETURN n", Map.of())))
            .hasMessageContaining("write");
        response("/db/neo4j/tx/commit", "{\"errors\":[{\"message\":\"Unknown property\"}],\"results\":[]}");
        assertThatThrownBy(() -> adapter.execute(definition(CapabilityType.GRAPH, "MATCH (n) RETURN n", Map.of()), Map.of()))
            .hasMessageContaining("Unknown property");
    }
    @Test void readsUpdatedAuthenticationFromTheSameCentralAsset() throws Exception {
        response("/db/neo4j/tx/commit", "{\"errors\":[],\"results\":[{\"columns\":[],\"data\":[]}]}");
        var adapter = new GraphQueryAdapter(connections, http, json);
        var definition = definition(CapabilityType.GRAPH, "MATCH (n) RETURN n", Map.of());
        connection.setHeadersJson("{\"Authorization\":\"Bearer first\"}");
        adapter.execute(definition, Map.of()); assertThat(authorization.get()).isEqualTo("Bearer first");
        connection.setHeadersJson("{\"Authorization\":\"Bearer second\"}");
        adapter.execute(definition, Map.of()); assertThat(authorization.get()).isEqualTo("Bearer second");
    }
    @Test void openSearchBindsVectorAndFilterValuesAndBoundsResults() throws Exception {
        response("/documents/_search", "{\"timed_out\":false,\"hits\":{\"total\":{\"value\":2,\"relation\":\"eq\"},\"hits\":[{\"_id\":\"1\",\"_score\":0.8,\"_source\":{\"title\":\"Report\"}}]}}");
        var adapter = new OpenSearchQueryAdapter(connections, http, json);
        var d = definition(CapabilityType.UNSTRUCTURED,
            "{\"query\":{\"knn\":{\"embedding\":{\"vector\":\"{{vector}}\",\"k\":2,\"filter\":{\"term\":{\"market\":\"{{market}}\"}}}}}}", Map.of("index", "documents"));
        adapter.validate(d);
        var result = adapter.execute(d, Map.of("vector", List.of(0.1, 0.2), "market", "SSE"));
        assertThat(request.get().at("/query/knn/embedding/vector").isArray()).isTrue();
        assertThat(request.get().at("/query/knn/embedding/filter/term/market").asText()).isEqualTo("SSE");
        assertThat(request.get().get("size").asInt()).isEqualTo(1);
        assertThat(result.truncated()).isTrue(); assertThat(result.rows().get(0)).containsEntry("title", "Report");
    }
    @Test void openSearchDoesNotTreatTimedOutPartialResultsAsSuccess() {
        response("/documents/_search", "{\"timed_out\":true,\"hits\":{\"hits\":[]}}");
        var adapter = new OpenSearchQueryAdapter(connections, http, json);
        assertThatThrownBy(() -> adapter.execute(definition(CapabilityType.UNSTRUCTURED, "{}", Map.of("index", "documents")), Map.of()))
            .hasMessageContaining("timed out");
        assertThatThrownBy(() -> adapter.validate(definition(CapabilityType.UNSTRUCTURED, "{}", Map.of("index", "../_all"))))
            .hasMessageContaining("index");
    }
}
