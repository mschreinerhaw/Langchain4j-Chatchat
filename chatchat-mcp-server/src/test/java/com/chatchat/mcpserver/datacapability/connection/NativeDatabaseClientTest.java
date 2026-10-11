package com.chatchat.mcpserver.datacapability.connection;

import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.*;
import org.neo4j.driver.types.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NativeDatabaseClientTest {
    @Test void existingOpenSearchTlsPolicyAppliesOnlyToTheExplicitlyConfiguredEndpoint() {
        var properties = new com.chatchat.mcpserver.search.engine.LuceneSearchProperties();
        properties.getOpenSearch().setUrl("https://configured.example:9200/");
        properties.getOpenSearch().setInsecureSsl(true);
        try (var client = new NativeDatabaseClient(new ObjectMapper())) {
            var selected = asset("opensearch", "https://configured.example:9200");
            assertThat(client.usesConfiguredOpenSearchTlsPolicy(selected, "opensearch")).isFalse();
            client.configureOpenSearch(properties);
            assertThat(client.usesConfiguredOpenSearchTlsPolicy(selected, "opensearch")).isTrue();
            assertThat(client.usesConfiguredOpenSearchTlsPolicy(selected, "elasticsearch")).isFalse();
            assertThat(client.usesConfiguredOpenSearchTlsPolicy(asset("opensearch", "https://other.example:9200"), "opensearch")).isFalse();
            assertThat(client.usesConfiguredOpenSearchTlsPolicy(asset("opensearch", "https://configured.example:9201"), "opensearch")).isFalse();
            properties.getOpenSearch().setInsecureSsl(false);
            assertThat(client.usesConfiguredOpenSearchTlsPolicy(selected, "opensearch")).isFalse();
        }
    }
    private final ObjectMapper json = new ObjectMapper();
    private HttpEndpointConfig asset(String kind, String uri) {
        var asset = new HttpEndpointConfig(); asset.setId("asset"); asset.setCategory(kind);
        asset.setUrlTemplate(uri); asset.setTimeoutMs(5000); return asset;
    }
    @ParameterizedTest @ValueSource(strings = {"opensearch", "elasticsearch"})
    void officialClientsPreservePrefixQueryParametersAndUpdatedAuthentication(String kind) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>(), authorization = new ArrayList<>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var client = new NativeDatabaseClient(json)) {
            var asset = asset(kind, "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway");
            asset.setHeadersJson("{\"Authorization\":\"Bearer first\"}");
            assertThat(client.get(asset, "/news/_field_caps?fields=*&include_unmapped=true", 5).path("ok").asBoolean()).isTrue();
            asset.setHeadersJson("{\"Authorization\":\"Bearer second\"}");
            client.post(asset, "/news/_search", Map.of("size", 1), 5, Map.of());
            assertThat(paths.get(0)).startsWith("/gateway/news/_field_caps?").contains("include_unmapped=true");
            assertThat(paths.get(1)).isEqualTo("/gateway/news/_search");
            assertThat(authorization).containsExactly("Bearer first", "Bearer second");
        } finally { server.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings = {"opensearch", "elasticsearch"})
    void requestDeadlineCancelsPendingSearchAndAllowsLaterRequests(String kind) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        var pending = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/slow")) pending.await(3, TimeUnit.SECONDS);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (Exception ignored) { } finally { exchange.close(); }
        }); server.start();
        try (var client = new NativeDatabaseClient(json)) {
            var asset = asset(kind, "http://127.0.0.1:" + server.getAddress().getPort());
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.get(asset, "/slow", 1)).isInstanceOfAny(TimeoutException.class, java.net.SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            pending.countDown();
            assertThat(client.get(asset, "/fast", 1).isObject()).isTrue();
        } finally { pending.countDown(); server.stop(0); executor.shutdownNow(); }
    }
    @Test void boltUsesReadSessionTransactionTimeoutBoundParametersAndTypedGraphValues() throws Exception {
        var driver = mock(Driver.class); var session = mock(Session.class); var tx = mock(TransactionContext.class);
        var result = mock(Result.class); var record = mock(org.neo4j.driver.Record.class);
        var first = mock(Node.class); var second = mock(Node.class); var relationship = mock(Relationship.class);
        when(first.elementId()).thenReturn("node:a"); when(first.labels()).thenReturn(List.of("Person"));
        when(first.asMap()).thenReturn(Map.of("name", "Alice"));
        when(second.elementId()).thenReturn("node:b"); when(second.labels()).thenReturn(List.of("Company"));
        when(second.asMap()).thenReturn(Map.of("name", "Company"));
        when(relationship.elementId()).thenReturn("rel:a"); when(relationship.type()).thenReturn("WORKS_AT");
        when(relationship.startNodeElementId()).thenReturn("node:a"); when(relationship.endNodeElementId()).thenReturn("node:b");
        when(relationship.asMap()).thenReturn(Map.of("since", 2020));
        var entities = mock(Value.class);
        when(entities.asObject()).thenReturn(List.of(first, relationship, second));
        when(record.get("entities")).thenReturn(entities);
        when(record.get("missing")).thenReturn(Values.NULL);
        when(result.keys()).thenReturn(List.of("entities", "missing"));
        when(result.hasNext()).thenReturn(true, false); when(result.next()).thenReturn(record);
        when(tx.run(anyString(), anyMap())).thenReturn(result);
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        when(session.executeRead(any(TransactionCallback.class), any(TransactionConfig.class))).thenAnswer(call ->
            ((TransactionCallback<?>) call.getArgument(0)).execute(tx));
        AtomicReference<Config> configuration = new AtomicReference<>();
        try (var client = new NativeDatabaseClient(json) {
            @Override protected Driver createDriver(URI uri, AuthToken auth, Config config) {
                assertThat(uri.toString()).isEqualTo("bolt://localhost:7687"); configuration.set(config); return driver;
            }
        }) {
            var response = client.cypher(asset("neo4j", "bolt://localhost:7687"), "analytics",
                List.of(Map.of("statement", "RETURN $name AS entities", "parameters", Map.of("name", "Alice' DELETE n"))), 2);
            assertThat(response.at("/results/0/data/0/graph/nodes").size()).isEqualTo(2);
            assertThat(response.at("/results/0/data/0/graph/relationships/0/startNode").asText()).isEqualTo("node:a");
            assertThat(response.at("/results/0/data/0/row/1").isNull()).isTrue();
            verify(tx).run("RETURN $name AS entities", Map.of("name", "Alice' DELETE n"));
            verify(driver).session(argThat((SessionConfig c) -> c.database().orElseThrow().equals("analytics") && c.defaultAccessMode() == AccessMode.READ));
            verify(session).executeRead(any(), argThat(c -> c.timeout().equals(Duration.ofSeconds(2))));
            verify(session).close(); verify(result).consume();
            assertThat(configuration.get().maxTransactionRetryTimeMillis()).isZero();
        }
        verify(driver).close();
    }
    @Test void legacyGraphHttpAddressIsRejectedBeforeCreatingADriver() {
        try (var client = new NativeDatabaseClient(json)) {
            assertThatThrownBy(() -> client.cypher(asset("neo4j", "http://localhost:7474"), "neo4j",
                List.of(Map.of("statement", "RETURN 1")), 1)).hasMessageContaining("Bolt/neo4j");
        }
    }
    @Test void realBundledBoltTransportLoadsAndFailsAnUnavailableEndpointWithoutLinkageErrors() {
        try (var client = new NativeDatabaseClient(json)) {
            assertThatThrownBy(() -> client.cypher(asset("neo4j", "bolt://127.0.0.1:1"), "neo4j",
                List.of(Map.of("statement", "RETURN 1")), 1))
                .isInstanceOf(org.neo4j.driver.exceptions.ServiceUnavailableException.class);
        }
    }
}
