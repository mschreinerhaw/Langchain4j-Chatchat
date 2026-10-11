package com.chatchat.mcpserver.sql.datasource;

import com.chatchat.mcpserver.category.*;
import com.chatchat.mcpserver.routing.target.ExecutionTargetService;
import com.chatchat.mcpserver.sql.metadata.SqlMetadataAssetRegistryService;
import com.chatchat.mcpserver.datacapability.connection.NativeDatabaseClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeQueryDatasourceTest {
    private SqlDatasourceConfig asset(String type, String address) {
        var asset = new SqlDatasourceConfig();
        asset.setName(type); asset.setToolName("db_query_" + type);
        asset.setDatabaseType(type); asset.setDriverClass(type + "-http");
        asset.setJdbcUrl(address); asset.setUsername("reader"); asset.setPassword("secret");
        asset.setMetadataAutoRefreshEnabled(true);
        return asset;
    }

    @ParameterizedTest @ValueSource(strings = {"neo4j", "opensearch", "elasticsearch"})
    void savesInExistingDatabaseAssetsWithNativeMetadataRefresh(String type) {
        var repository = mock(SqlDatasourceConfigRepository.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var metadata = mock(SqlMetadataAssetRegistryService.class);
        var categories = mock(BusinessCategoryService.class);
        var category = new BusinessCategory(); category.setId("finance");
        when(categories.resolveOrDefault(any())).thenReturn(category);
        var service = new SqlDatasourceConfigService(repository, new ObjectMapper(), mock(ExecutionTargetService.class), metadata, categories);
        var saved = service.create(asset(type, type.equals("neo4j") ? "bolt://localhost:7687" : "https://localhost:9200"));
        assertThat(saved.getDatabaseType()).isEqualTo(type);
        assertThat(saved.getDriverClass()).isEqualTo(type + "-native");
        assertThat(saved.isMetadataAutoRefreshEnabled()).isTrue();
        assertThat(saved.getMetadataScopeType()).isEqualTo(type.equals("neo4j") ? "GRAPH_DATABASE" : "SEARCH_INDEX");
        assertThat(saved.getCapabilitiesJson()).contains("database_query").doesNotContain("jdbc");
        assertThat(saved.getPassword()).isEqualTo("secret");
        verify(metadata).syncDefaultForDatasource(saved);
    }

    @ParameterizedTest @ValueSource(strings = {"opensearch", "elasticsearch"})
    void connectionTestUsesNativeProtocolAndAssetCredentials(String type) throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new ArrayList<String>();
        server.createContext("/", exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            received.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (type.equals("neo4j")) received.add(json.readTree(exchange.getRequestBody()).at("/statements/0/statement").asText());
            byte[] body = (type.equals("neo4j") ? "{\"errors\":[],\"results\":[{\"columns\":[\"probe\"],\"data\":[{\"row\":[1]}]}]}" : "{\"cluster_name\":\"local\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try (var client = new NativeDatabaseClient(json)) {
            var result = new NativeQueryDatasourceProbe(client).test(asset(type, "http://127.0.0.1:" + server.getAddress().getPort()));
            assertThat(result.success()).as(result.errorMessage()).isTrue();
            assertThat(received.get(0)).isEqualTo(type.equals("neo4j") ? "POST /db/neo4j/tx/commit" : "GET /");
            assertThat(received.get(1)).isEqualTo("Basic " + Base64.getEncoder().encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8)));
            if (type.equals("neo4j")) assertThat(received.get(2)).isEqualTo("RETURN 1 AS probe");
        } finally { server.stop(0); }
    }

    @Test void invalidAddressesAndMismatchedDriversAreRejected() {
        var asset = asset("neo4j", "jdbc:mysql://localhost/db");
        assertThatThrownBy(() -> NativeQueryDatasource.normalize(asset)).isInstanceOf(IllegalArgumentException.class);
        asset.setJdbcUrl("http://user:password@localhost:7474");
        assertThatThrownBy(() -> NativeQueryDatasource.normalize(asset)).isInstanceOf(IllegalArgumentException.class);
        asset.setJdbcUrl("https://localhost:9200"); asset.setDatabaseType("elasticsearch");
        assertThatThrownBy(() -> NativeQueryDatasource.normalize(asset)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void neo4jRejectsLegacyHttpAndAcceptsSecureBoltWithoutDynamicDriverLoading() {
        var asset = asset("neo4j", "http://localhost:7474");
        assertThatThrownBy(() -> NativeQueryDatasource.normalize(asset)).hasMessageContaining("Bolt/neo4j");
        asset.setJdbcUrl("neo4j+s://localhost:7687");
        NativeQueryDatasource.normalize(asset);
        assertThat(asset.getDriverClass()).isEqualTo("neo4j-native");
        assertThat(NativeQueryDatasource.connection(asset).getUrlTemplate()).isEqualTo("neo4j+s://localhost:7687");
    }

    @Test void neo4jProbeUsesSelectedDatabaseAndBoltClient() throws Exception {
        var client = mock(NativeDatabaseClient.class);
        var asset = asset("neo4j", "bolt://localhost:7687");
        asset.setMetadataScopeValue("analytics,neo4j");
        when(client.cypher(any(), eq("analytics"), anyList(), anyInt())).thenReturn(new ObjectMapper().readTree(
            "{\"errors\":[],\"results\":[{\"columns\":[\"probe\"],\"data\":[{\"row\":[1]}]}]}"));
        assertThat(new NativeQueryDatasourceProbe(client).test(asset).success()).isTrue();
        verify(client).cypher(any(), eq("analytics"), eq(List.of(Map.of("statement", "RETURN 1 AS probe"))), anyInt());
        verify(client, never()).post(any(), anyString(), any(), anyInt(), anyMap());
    }
}
