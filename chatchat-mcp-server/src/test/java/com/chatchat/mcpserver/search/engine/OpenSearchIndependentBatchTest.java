package com.chatchat.mcpserver.search.engine;

import com.chatchat.common.retrieval.query.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class OpenSearchIndependentBatchTest {
    private final ObjectMapper json = new ObjectMapper();
    private LuceneMcpSearchService.AssetSearchRequest request(String query) {
        return new LuceneMcpSearchService.AssetSearchRequest("sql_datasource", query, "production", "mysql", List.of("authorized"), 10);
    }
    @Test void realClientSendsNdjsonWithPerBatchFiltersAndConcurrencyLimit() throws Exception {
        var captured = new AtomicReference<String>(); var uri = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            uri.set(exchange.getRequestURI().toString());
            var hit = Map.of("_index", "assets", "_id", "asset-1", "_score", 3, "_source", Map.of("id", "asset-1", "kind", "asset", "assetType", "sql_datasource"));
            byte[] response = json.writeValueAsBytes(Map.of("responses", List.of(Map.of("hits", Map.of("hits", List.of(hit))), Map.of("hits", Map.of("hits", List.of(hit))))));
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        var properties = new LuceneSearchProperties(); properties.setEngine("opensearch");
        properties.getOpenSearch().setUrl("http://127.0.0.1:"+server.getAddress().getPort());
        var service = new OpenSearchMcpSearchService(properties, new McpEmbeddingClient(properties, json), json);
        try {
            var result = service.searchAssetBatches(List.of(request("orders"), request("customers")), List.of("asset-1"));
            assertThat(result).hasSize(2).allSatisfy(hits -> assertThat(hits).hasSize(1));
            assertThat(uri.get()).startsWith("/_msearch?max_concurrent_searches=");
            assertThat(captured.get()).endsWith("\n");
            String[] lines = captured.get().split("\n"); assertThat(lines).hasSize(4);
            for (int i : List.of(1,3)) {
                var body = json.readTree(lines[i]);
                assertThat(body.path("query").toString()).contains("production", "mysql", "authorized", "asset-1");
                assertThat(body.path("timeout").asText()).endsWith("ms");
            }
        } finally { service.close(); server.stop(0); }
    }

    @Test void oversizedIndependentIntentSetIsRejectedBeforeNetwork() {
        var properties = new LuceneSearchProperties(); properties.setEngine("opensearch");
        var service = new OpenSearchMcpSearchService(properties, new McpEmbeddingClient(properties, json), json);
        var budget = new QueryOptimizationBudget(); budget.setMaxBatches(1);
        try (var optimizer = new SearchQueryOptimizer(budget, null)) {
            service.setQueryOptimizer(optimizer);
            assertThatThrownBy(() -> service.searchAssetBatches(List.of(request("one"), request("two")), List.of("asset-1")))
                .hasMessageContaining("QUERY_BATCH_BUDGET_EXCEEDED");
        } finally { service.close(); }
    }
}
