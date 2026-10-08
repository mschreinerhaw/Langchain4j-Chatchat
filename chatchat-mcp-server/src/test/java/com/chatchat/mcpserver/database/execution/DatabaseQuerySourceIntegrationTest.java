package com.chatchat.mcpserver.database.execution;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.cache.query.DatabaseQueryCacheService;
import com.chatchat.mcpserver.database.definition.*;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigRepository;
import com.chatchat.mcpserver.datacapability.connection.*;
import com.chatchat.mcpserver.datacapability.definition.CapabilityType;
import com.chatchat.mcpserver.datacapability.graph.GraphQueryAdapter;
import com.chatchat.mcpserver.datacapability.unstructured.OpenSearchQueryAdapter;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.sql.calendar.DynamicDateParamService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.NativeQueryDatasource;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.chatchat.mcpserver.sql.execution.SqlScriptExecuteService;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DatabaseQuerySourceIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final QueryConnectionService connections = mock(QueryConnectionService.class);
    private final SqlDatasourceConfigService sqlAssets = mock(SqlDatasourceConfigService.class);
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final DatabaseQueryCacheService cache = mock(DatabaseQueryCacheService.class);
    private DatabaseQuerySourceAdapterService adapters;
    private DatabaseQueryInvokeService invoke;
    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var asset = new HttpEndpointConfig();
        asset.setId("asset"); asset.setEnabled(true); asset.setTimeoutMs(5000);
        asset.setUrlTemplate("http://127.0.0.1:" + server.getAddress().getPort());
        when(connections.get(eq("asset"), any(), anyBoolean())).thenReturn(asset);
        var http = new QueryHttpClient(json);
        adapters = new DatabaseQuerySourceAdapterService(sqlAssets, connections,
            new GraphQueryAdapter(connections, http, json), new OpenSearchQueryAdapter(connections, http, json));
        invoke = new DatabaseQueryInvokeService(tools, sqlAssets, mock(SqlScriptExecuteService.class),
            new DynamicDateParamService(mock(DynamicJdbcDriverLoader.class)), json, mock(InvocationAuditService.class), cache);
        ReflectionTestUtils.setField(invoke, "sourceAdapters", adapters);
        when(cache.getOrLoad(any(), anyMap(), any(), any())).thenAnswer(call -> ((Supplier<ToolOutput>) call.getArgument(3)).get());
    }

    @AfterEach void stop() { server.stop(0); }

    private void serve(String path, String response) {
        server.createContext(path, exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
    }

    private DatabaseQuerySqlStep step(String code, String query) {
        var step = new DatabaseQuerySqlStep();
        step.setSqlCode(code); step.setSqlName(code); step.setSqlDescription("查询步骤");
        step.setSqlContent(query); step.setExecutionOrder(1); step.setWorkflowEnabled(true);
        step.setTimeoutSeconds(5); step.setMaxResultRows(10);
        return step;
    }

    private DatabaseQueryConfig config(List<DatabaseQuerySqlStep> steps) throws Exception {
        var config = new DatabaseQueryConfig();
        config.setId("query"); config.setToolName("query_company"); config.setTitle("公司查询");
        config.setDescription("公司查询分析"); config.setImplementationSteps("获取公司并查询关联关系");
        config.setDatasourceId("http:asset"); config.setSqlStepsJson(json.writeValueAsString(steps));
        config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        config.setMaxRows(10); config.setTimeoutSeconds(5);
        return config;
    }

    @Test void graphUsesExistingWorkflowDependenciesAndStructuredPreview() throws Exception {
        when(connections.list(null)).thenReturn(List.of(new QueryConnectionService.AssetReference("asset", "图资产", CapabilityType.GRAPH, true)));
        serve("/db/neo4j/tx/commit", "{\"errors\":[],\"results\":[{\"columns\":[\"id\"],\"data\":[{\"row\":[\"company-1\"]}]}]}");
        var first = step("COMPANY", "MATCH (c:Company) WHERE c.name = $name RETURN c.id AS id");
        first.setParameters(Map.of("name", "Alice' DELETE c"));
        first.setReturnToModel(false);
        var second = step("RELATIONS", "MATCH (c:Company) WHERE c.id = $id RETURN c.id AS id");
        second.setExecutionOrder(2); second.setDependencies(List.of("COMPANY"));
        var mapping = new DatabaseQueryParameterMapping();
        mapping.setParameter("id"); mapping.setSourceType("UPSTREAM_RESULT"); mapping.setSourceNode("COMPANY");
        mapping.setSourceExpression("$.rows[0].id"); mapping.setRequired(true);
        second.setParameterMappings(List.of(mapping));
        var result = invoke.invokePreview(config(List.of(first, second)), Map.of());
        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).at("/statements/0/parameters/name").asText()).isEqualTo("Alice' DELETE c");
        assertThat(requests.get(1).at("/statements/0/parameters/id").asText()).isEqualTo("company-1");
        var data = json.valueToTree(result.getData());
        assertThat(data.path("resultSets").size()).isEqualTo(1);
        assertThat(data.path("allResultSets").size()).isEqualTo(2);
        assertThat(data.path("executionMode").asText()).isEqualTo("DEPENDENCY_GRAPH");
        verifyNoInteractions(tools, sqlAssets);
    }

    @Test void openSearchRetainsVectorTypesInOriginalWorkflowAndRejectsInvalidIndex() throws Exception {
        when(connections.list(null)).thenReturn(List.of(new QueryConnectionService.AssetReference("asset", "检索资产", CapabilityType.UNSTRUCTURED, true)));
        serve("/documents/_search", "{\"timed_out\":false,\"hits\":{\"total\":1,\"hits\":[{\"_id\":\"doc-1\",\"_source\":{\"title\":\"Report\"}}]}}");
        var step = step("SEARCH", "{\"query\":{\"knn\":{\"embedding\":{\"vector\":\"{{vector}}\",\"k\":2}}}}");
        step.setParameters(Map.of("vector", List.of(0.1, 0.2)));
        step.setQueryOptions(Map.of("index", "documents"));
        var result = invoke.invokePreview(config(List.of(step)), Map.of());
        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(requests.get(0).at("/query/knn/embedding/vector").isArray()).isTrue();
        assertThat(json.valueToTree(result.getData()).at("/rows/0/title").asText()).isEqualTo("Report");
        step.setQueryOptions(Map.of("index", "../_all"));
        assertThatThrownBy(() -> adapters.validate("http:asset", List.of(step), null)).hasMessageContaining("index");
    }

    @Test void unifiedSaveKeepsOptionsAndTypedParametersInExistingQueryRegistry() throws Exception {
        when(connections.list(null)).thenReturn(List.of(new QueryConnectionService.AssetReference("asset", "检索资产", CapabilityType.UNSTRUCTURED, true)));
        var repository = mock(DatabaseQueryConfigRepository.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = new DatabaseQueryConfigService(repository, mock(ApiServiceConfigRepository.class), tools, json, sqlAssets);
        ReflectionTestUtils.setField(service, "sourceAdapters", adapters);
        var step = step("SEARCH", "{\"query\":{\"match_all\":{}}}");
        step.setParameters(Map.of("vector", List.of(0.1, 0.2))); step.setQueryOptions(Map.of("index", "documents"));
        var saved = service.create(config(List.of(step)));
        assertThat(saved.getDatabaseType()).isEqualTo("opensearch");
        assertThat(json.readTree(saved.getSqlStepsJson()).at("/0/queryOptions/index").asText()).isEqualTo("documents");
        assertThat(json.readTree(saved.getSqlStepsJson()).at("/0/parameters/vector").isArray()).isTrue();
        when(repository.findByEnabledTrueOrderByToolNameAsc()).thenReturn(List.of(saved));
        assertThat(service.listEnabled()).containsExactly(saved);
        verifyNoInteractions(sqlAssets);
    }

    @Test void referencedHttpAssetCannotBeDeletedAndDisabledAssetsCannotExecute() throws Exception {
        when(connections.list(null)).thenReturn(List.of(new QueryConnectionService.AssetReference("asset", "图资产", CapabilityType.GRAPH, false)));
        when(connections.get("asset", CapabilityType.GRAPH, true)).thenThrow(new IllegalArgumentException("disabled"));
        assertThatThrownBy(() -> adapters.getEnabled("http:asset")).hasMessageContaining("disabled");
        var definitions = mock(com.chatchat.mcpserver.datacapability.definition.CapabilityRepository.class);
        var repository = mock(DatabaseQueryConfigRepository.class);
        when(repository.findAllByOrderByToolNameAsc()).thenReturn(List.of(config(List.of(step("READ", "RETURN 1")))));
        var guard = new CapabilityAssetReferenceGuard(definitions, json);
        ReflectionTestUtils.setField(guard, "queryDefinitions", repository);
        assertThatThrownBy(() -> guard.assertUnused("asset", false)).hasMessageContaining("query_company");
        guard.assertUnused("asset", true);
    }

    @ParameterizedTest @ValueSource(strings = {"neo4j", "opensearch", "elasticsearch"})
    void databaseAssetsUseNativeDriversInOriginalWorkflow(String type) throws Exception {
        var database = new SqlDatasourceConfig();
        database.setId("native"); database.setName(type); database.setEnabled(true);
        database.setDatabaseType(type); database.setDriverClass(type + "-http");
        database.setJdbcUrl("http://127.0.0.1:" + server.getAddress().getPort());
        database.setUsername("reader"); database.setPassword("secret");
        when(sqlAssets.getEnabled("native")).thenReturn(database);
        when(sqlAssets.getById("native")).thenReturn(database);
        when(sqlAssets.listAll()).thenReturn(List.of(database));
        var legacyHttp = mock(HttpEndpointConfigService.class);
        var centralConnections = new QueryConnectionService(legacyHttp);
        ReflectionTestUtils.setField(centralConnections, "databaseAssets", sqlAssets);
        var http = new QueryHttpClient(json);
        adapters = new DatabaseQuerySourceAdapterService(sqlAssets, centralConnections,
            new GraphQueryAdapter(centralConnections, http, json), new OpenSearchQueryAdapter(centralConnections, http, json));
        ReflectionTestUtils.setField(invoke, "sourceAdapters", adapters);
        assertThat(adapters.list()).singleElement().satisfies(source -> {
            assertThat(source.id()).isEqualTo("native");
            assertThat(source.databaseType()).isEqualTo(type);
            assertThat(source.queryLanguage()).isEqualTo(type.equals("neo4j") ? "Cypher" : "JSON DSL");
        });
        String path = type.equals("neo4j") ? "/db/neo4j/tx/commit" : "/documents/_search";
        String response = type.equals("neo4j")
            ? "{\"errors\":[],\"results\":[{\"columns\":[\"title\"],\"data\":[{\"row\":[\"Report\"]}]}]}"
            : "{\"hits\":{\"total\":1,\"hits\":[{\"_id\":\"doc-1\",\"_source\":{\"title\":\"Report\"}}]}}";
        var authHeaders = new ArrayList<String>();
        server.createContext(path, exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var step = step("READ", type.equals("neo4j") ? "RETURN $name AS title" : "{\"query\":{\"terms\":{\"tags\":\"{{tags}}\"}}}");
        step.setParameters(type.equals("neo4j") ? Map.of("name", "Report") : Map.of("tags", List.of("finance", "news")));
        step.setQueryOptions(type.equals("neo4j") ? Map.of("database", "neo4j") : Map.of("index", "documents"));
        var configuration = config(List.of(step)); configuration.setDatasourceId("native");
        var result = invoke.invokePreview(configuration, Map.of());
        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(json.valueToTree(result.getData()).at("/rows/0/title").asText()).isEqualTo("Report");
        assertThat(authHeaders).containsExactly("Basic " + Base64.getEncoder().encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8)));
        if (!type.equals("neo4j")) assertThat(requests.get(0).at("/query/terms/tags").isArray()).isTrue();
        database.setPassword("changed");
        assertThat(centralConnections.get("db:native", type.equals("neo4j") ? CapabilityType.GRAPH : CapabilityType.UNSTRUCTURED, true).getHeadersJson())
            .contains(Base64.getEncoder().encodeToString("reader:changed".getBytes(StandardCharsets.UTF_8)));
        verifyNoInteractions(tools);
        verify(legacyHttp, never()).getById(anyString());
        verify(legacyHttp, never()).create(any());
    }
}
