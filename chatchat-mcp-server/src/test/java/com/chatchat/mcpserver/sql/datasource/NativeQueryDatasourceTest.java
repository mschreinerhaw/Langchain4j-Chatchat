package com.chatchat.mcpserver.sql.datasource;

import com.chatchat.mcpserver.category.*;
import com.chatchat.mcpserver.routing.target.ExecutionTargetService;
import com.chatchat.mcpserver.sql.metadata.SqlMetadataAssetRegistryService;
import com.chatchat.mcpserver.datacapability.connection.QueryHttpClient;
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
    void savesInExistingDatabaseAssetsWithoutJdbcMetadata(String type) {
        var repository = mock(SqlDatasourceConfigRepository.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var metadata = mock(SqlMetadataAssetRegistryService.class);
        var categories = mock(BusinessCategoryService.class);
        var category = new BusinessCategory(); category.setId("finance");
        when(categories.resolveOrDefault(any())).thenReturn(category);
        var service = new SqlDatasourceConfigService(repository, new ObjectMapper(), mock(ExecutionTargetService.class), metadata, categories);
        var saved = service.create(asset(type, "https://localhost:9200"));
        assertThat(saved.getDatabaseType()).isEqualTo(type);
        assertThat(saved.getDriverClass()).isEqualTo(type + "-http");
        assertThat(saved.isMetadataAutoRefreshEnabled()).isFalse();
        assertThat(saved.getCapabilitiesJson()).contains("database_query").doesNotContain("jdbc");
        assertThat(saved.getPassword()).isEqualTo("secret");
        verifyNoInteractions(metadata);
    }

    @ParameterizedTest @ValueSource(strings = {"neo4j", "opensearch", "elasticsearch"})
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
        try {
            var result = new NativeQueryDatasourceProbe(new QueryHttpClient(json)).test(asset(type, "http://127.0.0.1:" + server.getAddress().getPort()));
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
}
