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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BuiltInToolsBootstrapTest {

    private HttpServer server;
    private int documentLoginCount;
    private String documentSearchAuthorization;
    private String documentDetailAuthorization;
    private String searchEngineQuery;
    private String siteSearchKeyword;
    private String siteSearchRawQuery;
    private int searchLandingCount;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void builtInToolsExposeGovernanceMetadataWithoutLegacyWebSearch() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
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

        assertThat(registry.hasTool("web_search")).isFalse();

        ToolMetadata databaseQuery = registry.getToolMetadata("database_query");
        assertThat(databaseQuery.getCategory()).isEqualTo("database_data_query");
        assertThat(databaseQuery.getRiskLevel()).isEqualTo("high");
        assertThat(databaseQuery.getOperationType()).isEqualTo("read");
        assertThat(databaseQuery.isUserVisible()).isFalse();
        assertThat(databaseQuery.isAgentCompatible()).isFalse();
        assertThat(databaseQuery.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(databaseQuery.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(databaseQuery.getOutputPolicy()).containsKey("mask_fields");
        assertThat(databaseQuery.getParameters()).extracting("name")
            .contains("jdbc_url", "driver_class", "database_type", "reload_drivers");

        ToolMetadata fileSystem = registry.getToolMetadata("file_system");
        assertThat(fileSystem.getCategory()).isEqualTo("local_file_system");
        assertThat(fileSystem.getRiskLevel()).isEqualTo("high");
        assertThat(fileSystem.getOperationType()).isEqualTo("read");
        assertThat(fileSystem.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(fileSystem.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(fileSystem.getOutputPolicy()).containsKey("mask_fields");
    }

    @Test
    void databaseQueryAllowsMultilineDamengSelectTemplates() throws Exception {
        String sql = """
            select
              etl_date,
              fund_code,
              fund_name,
              valu_sys_nav,
              corp_offweb_nav,
              ta_sys_nav,
              xbrl_sys_nav,
              veri_rslt,
              diff_expl,
              updt_time
            from
              GDP_DWD.dwd_fund_nav_consistency_check_d_i
            where etl_date = to_date('20260520', 'YYYYMMDD')
            """;

        assertThat(validateDatabaseQuerySql(sql)).isEqualTo(sql.trim());
    }

    @Test
    void databaseQueryStillRejectsWriteSqlAfterReadOnlyTokenFix() {
        assertThatThrownBy(() -> validateDatabaseQuerySql("select * from demo; update demo set name = 'x'"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only one SQL statement is allowed");
        assertThatThrownBy(() -> validateDatabaseQuerySql("update demo set name = 'x'"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only read-only SQL statements are allowed");
    }

    @Test
    void databaseQueryFailureMessageIncludesTheUnderlyingJdbcReason() throws Exception {
        Object tool = databaseQueryTool();
        Method formatter = tool.getClass().getDeclaredMethod("databaseQueryFailureMessage", Exception.class);
        formatter.setAccessible(true);
        SQLException sqlException = new SQLException("SemanticException: incompatible comparison types", "42000", 10014);

        String message = (String) formatter.invoke(tool,
            new RuntimeException("PreparedStatementCallback; bad SQL grammar", sqlException));

        assertThat(message)
            .contains("PreparedStatementCallback; bad SQL grammar")
            .contains("SemanticException: incompatible comparison types")
            .contains("SQLState=42000")
            .contains("errorCode=10014");
    }

    @Test
    void databaseQueryRendersResolvedSqlPreviewWithoutChangingQuotedTextOrCasts() throws Exception {
        Object tool = databaseQueryTool();
        Method renderer = tool.getClass().getDeclaredMethod("renderSqlPreview", String.class, Map.class);
        renderer.setAccessible(true);

        String sql = (String) renderer.invoke(tool,
            "select ':ignored' note where busi_date = :busi_date and name = :name and code::text = :code",
            Map.of("busi_date", "20260105", "name", "O'Reilly", "code", 7));

        assertThat(sql).isEqualTo(
            "select ':ignored' note where busi_date = '20260105' and name = 'O''Reilly' and code::text = 7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentSearchEnrichesResultsWithDocumentContentExcerpts() throws Exception {
        startDocumentApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("chatchat.tools.document-search.api-base-url", "http://localhost:" + server.getAddress().getPort())
            .withProperty("chatchat.tools.document-search.auth.username", "test-user")
            .withProperty("chatchat.tools.document-search.auth.password", "test-password")
            .withProperty("chatchat.tools.document-search.default-excerpt-chars", "120")
            .withProperty("chatchat.tools.document-search.max-excerpt-chars", "200");

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            environment,
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool documentSearch = registry.getEnhancedTool("document_search");
        ToolOutput output = documentSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "Studio config file update", "limit", 1))
            .build());

        assertThat(output.isSuccess()).as(output.getErrorMessage()).isTrue();
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

    private String validateDatabaseQuerySql(String sql) throws Exception {
        Object tool = databaseQueryTool();
        Method validator = tool.getClass().getDeclaredMethod("validateReadOnlySql", String.class);
        validator.setAccessible(true);
        try {
            return (String) validator.invoke(tool, sql);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private Object databaseQueryTool() throws Exception {
        Class<?> toolClass = Class.forName("com.chatchat.tools.builtin.BuiltInToolsBootstrap$DatabaseQueryTool");
        Constructor<?> constructor = toolClass.getDeclaredConstructor(
            DynamicJdbcDriverLoader.class,
            DatabaseToolProperties.class,
            String.class,
            ObjectMapper.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            mock(DynamicJdbcDriverLoader.class),
            new DatabaseToolProperties(),
            "",
            new ObjectMapper()
        );
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
                {"data":{"keyword":"Studio config file update","results":[{"docId":"doc-1","title":"LiveData Studio Deployment","summary":"summary only","detailPath":"/api/v1/search/documents/doc-1"}],"total":1,"limit":1}}
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
                {"data":{"docId":"doc-1","title":"LiveData Studio Deployment","content":"Deployment requires editing Studio config file application.yml, copying livedata-stream to /home/livedata, configuring connection, port, and service path, then restarting Prometheus service."}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

}
