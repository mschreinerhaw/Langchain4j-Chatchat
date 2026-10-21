package com.chatchat.chat.interaction.service;

import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.infrastructure.opensearch.OpenSearchEmbeddingClient;
import com.chatchat.knowledgebase.search.query.application.SearchTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class McpToolSemanticIndexTest {
    @Test
    void negatedAnalysisInstructionsUseOnlyDatabaseAllowedToolsWithoutIssuingAnUnsafeSearch() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var searches=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();
            boolean head="HEAD".equals(exchange.getRequestMethod());
            exchange.getRequestBody().readAllBytes();
            if(path.endsWith("/_search"))searches.incrementAndGet();
            byte[] response=(path.equals("/_bulk")?"{\"errors\":false}":"{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(head?404:200,head?-1:response.length);
            if(!head)exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        SearchProperties properties=new SearchProperties();
        properties.getOpenSearch().setUrl("http://127.0.0.1:"+server.getAddress().getPort());
        var index=new McpToolSemanticIndex(properties,mock(OpenSearchEmbeddingClient.class),new SearchTokenizer(),new ObjectMapper(),new MockEnvironment());
        McpToolAsset allowed=new McpToolAsset();allowed.setId("allowed-id");allowed.setLocalToolName("market_query");allowed.setRemoteToolName("market_query");allowed.setDescription("顶点软件 股票行情 融资买入");
        String question="分析顶点软件（603383），不得把缺少数据判断为没有融资买入。"+"行情、融资买入、融资偿还、融资余额、投资机会。".repeat(7);
        try {
            assertThat(index.rank(question,List.of(allowed),3)).containsExactly("market_query");
            assertThat(searches.get()).isZero();
        } finally {index.close();server.stop(0);}
    }

    @Test
    void writesAndQueriesTheCompatibleIndexChosenBySchemaNegotiation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> bulkBody = new AtomicReference<>();
        AtomicReference<String> searchPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = "{}";
            int status = 200;
            if (method.equals("HEAD") && path.endsWith("_r1")) status = 404;
            else if (path.endsWith("/_mapping")) {
                String index = path.substring(1, path.indexOf("/_mapping"));
                response = "{\"" + index + "\":{\"mappings\":{\"properties\":{\"vector\":{\"type\":\"float\"}}}}}";
            } else if (path.endsWith("/_settings")) {
                String index = path.substring(1, path.indexOf("/_settings"));
                response = "{\"" + index + "\":{\"settings\":{\"index\":{\"knn\":\"false\"}}}}";
            } else if (path.equals("/_bulk")) { bulkBody.set(body); response = "{\"errors\":false}"; }
            else if (path.endsWith("/_search")) {
                searchPath.set(path);
                response = "{\"hits\":{\"hits\":[{\"_source\":{\"toolId\":\"allowed-id\"}}]}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, method.equals("HEAD") ? -1 : bytes.length);
            if (!method.equals("HEAD")) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        SearchProperties properties = new SearchProperties();
        properties.getOpenSearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getOpenSearch().getEmbedding().setEnabled(true);
        properties.getOpenSearch().getEmbedding().setDimension(2);
        var embedding = mock(OpenSearchEmbeddingClient.class);
        when(embedding.configured()).thenReturn(true);
        when(embedding.embed(anyString())).thenReturn(List.of(0.1F, 0.2F));
        var index = new McpToolSemanticIndex(properties, embedding, new SearchTokenizer(),
            new ObjectMapper(), new MockEnvironment());
        try {
            McpToolAsset tool = new McpToolAsset();
            tool.setId("allowed-id"); tool.setLocalToolName("generic_query"); tool.setRemoteToolName("generic_query");
            assertThat(index.rank("generic query", List.of(tool), 1)).containsExactly("generic_query");
            assertThat(bulkBody.get()).contains("_r1").contains("allowed-id");
            assertThat(searchPath.get()).endsWith("_r1/_search");
        } finally { index.close(); server.stop(0); }
    }
    @Test
    void indexesAndSearchesOnlyTheDatabaseAllowedToolIds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> searchBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = "{}";
            int status = 200;
            if ("HEAD".equals(method)) status = 404;
            else if (path.endsWith("/_search")) {
                searchBody.set(body);
                response = "{\"hits\":{\"hits\":[{\"_source\":{\"toolId\":\"allowed-id\"}}]}}";
            } else if (path.equals("/_bulk")) response = "{\"errors\":false}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, "HEAD".equals(method) ? -1 : bytes.length);
            if (!"HEAD".equals(method)) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            SearchProperties properties = new SearchProperties();
            properties.getOpenSearch().setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            McpToolSemanticIndex index = new McpToolSemanticIndex(properties,
                mock(OpenSearchEmbeddingClient.class), new SearchTokenizer(),
                new ObjectMapper(), new MockEnvironment());
            McpToolAsset allowed = new McpToolAsset();
            allowed.setId("allowed-id");
            allowed.setLocalToolName("customer_asset_query");
            allowed.setRemoteToolName("customer_asset_query");
            allowed.setDescription("Find customer assets");

            assertThat(index.rank("customer assets", List.of(allowed), 3))
                .containsExactly("customer_asset_query");
            assertThat(searchBody.get()).contains("allowed-id").doesNotContain("forbidden-id");
        } finally {
            server.stop(0);
        }
    }
}
