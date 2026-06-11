package com.chatchat.tools.builtin;

import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BuiltInToolsBootstrapTest {

    private HttpServer server;
    private int documentLoginCount;
    private String documentSearchAuthorization;
    private String documentDetailAuthorization;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void calculatorAndWebSearchExposeGovernanceMetadata() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            new WebSearchToolProperties(),
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );

        bootstrap.initializeBuiltInTools();

        ToolMetadata calculator = registry.getToolMetadata("calculator");
        assertThat(calculator.getCategory()).isEqualTo("utility_calculation");
        assertThat(calculator.getRiskLevel()).isEqualTo("low");
        assertThat(calculator.getOperationType()).isEqualTo("read");
        assertThat(calculator.getConfirmation()).containsEntry("default", "auto_execute");
        assertThat(calculator.getInputPolicy()).containsEntry("must_show_parameters", true);

        ToolMetadata webSearch = registry.getToolMetadata("web_search");
        assertThat(webSearch.getCategory()).isEqualTo("public_web_search");
        assertThat(webSearch.getRiskLevel()).isEqualTo("low");
        assertThat(webSearch.getOperationType()).isEqualTo("read");
        assertThat(webSearch.getConfirmation()).containsEntry("default", "auto_execute");
        assertThat(webSearch.getInputPolicy()).containsEntry("must_show_parameters", true);
        assertThat(webSearch.getInputPolicy()).containsKey("sensitive_params");
        assertThat(webSearch.getTimeoutMillis()).isGreaterThanOrEqualTo(60000L);

        ToolMetadata databaseQuery = registry.getToolMetadata("database_query");
        assertThat(databaseQuery.getCategory()).isEqualTo("database_data_query");
        assertThat(databaseQuery.getRiskLevel()).isEqualTo("high");
        assertThat(databaseQuery.getOperationType()).isEqualTo("read");
        assertThat(databaseQuery.isUserVisible()).isFalse();
        assertThat(databaseQuery.isAgentCompatible()).isFalse();
        assertThat(databaseQuery.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(databaseQuery.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(databaseQuery.getOutputPolicy()).containsKey("mask_fields");

        ToolMetadata fileSystem = registry.getToolMetadata("file_system");
        assertThat(fileSystem.getCategory()).isEqualTo("local_file_system");
        assertThat(fileSystem.getRiskLevel()).isEqualTo("high");
        assertThat(fileSystem.getOperationType()).isEqualTo("read");
        assertThat(fileSystem.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(fileSystem.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(fileSystem.getOutputPolicy()).containsKey("mask_fields");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentSearchEnrichesResultsWithDocumentContentExcerpts() throws Exception {
        startDocumentApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("chatchat.tools.document-search.api-base-url", "http://localhost:" + server.getAddress().getPort())
            .withProperty("chatchat.tools.document-search.default-excerpt-chars", "120")
            .withProperty("chatchat.tools.document-search.max-excerpt-chars", "200");

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            new WebSearchToolProperties(),
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            environment,
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool documentSearch = registry.getEnhancedTool("document_search");
        ToolOutput output = documentSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "Studio 配置文件修改", "limit", 1))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("contentMode", "detail_enriched");
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("detailFetched", true)
            .containsEntry("contentAvailable", true);
        assertThat((String) results.get(0).get("contentExcerpt"))
            .contains("application.yml")
            .contains("livedata-stream");
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) data.get("evidenceSnippets");
        assertThat(evidence).hasSize(1);
        assertThat((String) evidence.get(0).get("excerpt")).contains("application.yml");
        assertThat(documentLoginCount).isEqualTo(1);
        assertThat(documentSearchAuthorization).isEqualTo("Bearer doc-token");
        assertThat(documentDetailAuthorization).isEqualTo("Bearer doc-token");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchFetchesPageContentExcerpts() throws Exception {
        startWebSearchApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(3);
        webSearchProperties.setFetchPages(true);
        webSearchProperties.setMaxPagesToFetch(1);
        webSearchProperties.setPageExcerptChars(180);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "Kunpeng ARM compatibility", "num_results", 1))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("contentMode", "page_enriched")
            .containsEntry("page_excerpt_count", 1);
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("pageFetched", true)
            .containsEntry("pageContentAvailable", true);
        assertThat((String) results.get(0).get("pageExcerpt"))
            .contains("Kunpeng")
            .contains("ARM compatibility");
        List<Map<String, Object>> excerpts = (List<Map<String, Object>>) data.get("pageExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat((String) excerpts.get(0).get("excerpt")).contains("ARM compatibility");
    }

    private void startDocumentApi() throws IOException {
        documentLoginCount = 0;
        documentSearchAuthorization = null;
        documentDetailAuthorization = null;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/enterprise/auth/login", exchange -> {
            documentLoginCount++;
            String body = """
                {"code":200,"message":"login success","data":{"token":"doc-token","user":{"username":"admin"}}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/search", exchange -> {
            documentSearchAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer doc-token".equals(documentSearchAuthorization)) {
                byte[] bytes = "{\"code\":401,\"message\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            String body = """
                {"data":{"keyword":"Studio 配置文件修改","results":[{"docId":"doc-1","title":"LiveData Studio 部署","summary":"仅摘要","detailPath":"/api/v1/search/documents/doc-1"}],"total":1,"limit":1}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/search/documents/doc-1", exchange -> {
            documentDetailAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer doc-token".equals(documentDetailAuthorization)) {
                byte[] bytes = "{\"code\":401,\"message\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            String body = """
                {"data":{"docId":"doc-1","title":"LiveData Studio 部署","content":"部署时需要修改 Studio 配置文件 application.yml，将 livedata-stream 复制到 /home/livedata 后配置连接、端口和服务路径，然后重启 Prometheus 服务。"}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void startWebSearchApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            String body = """
                <html><body><ol><li class="b_algo"><h2><a href="http://localhost:%d/page1">Kunpeng compatibility guide</a></h2><div class="b_caption"><p>Compatibility notes for ARM.</p></div></li></ol></body></html>
                """.formatted(server.getAddress().getPort());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/page1", exchange -> {
            String body = """
                <html><head><title>Kunpeng compatibility guide</title></head><body><header>Navigation</header><main>Huawei Kunpeng CPU uses an ARM architecture. This page lists ARM compatibility requirements, operating system support, and deployment notes for enterprise software.</main><footer>Footer</footer></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
