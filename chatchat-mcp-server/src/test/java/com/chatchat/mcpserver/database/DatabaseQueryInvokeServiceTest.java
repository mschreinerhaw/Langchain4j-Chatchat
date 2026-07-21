package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.cache.DatabaseQueryCacheService;
import com.chatchat.mcpserver.sql.DynamicDateParamService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlScriptExecuteService;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseQueryInvokeServiceTest {

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final SqlScriptExecuteService scriptExecuteService = mock(SqlScriptExecuteService.class);
    private final InvocationAuditService auditService = mock(InvocationAuditService.class);
    private final DatabaseQueryCacheService cacheService = mock(DatabaseQueryCacheService.class);
    private final DatabaseQueryInvokeService service = new DatabaseQueryInvokeService(
        toolRegistry,
        datasourceConfigService,
        scriptExecuteService,
        new DynamicDateParamService(mock(DynamicJdbcDriverLoader.class)),
        new ObjectMapper(),
        auditService,
        cacheService
    );

    @BeforeEach
    void setUpCache() {
        when(cacheService.get(org.mockito.ArgumentMatchers.any(DatabaseQueryConfig.class), anyMap()))
            .thenReturn(Optional.empty());
    }

    @Test
    void passesDatasourceDatabaseTypeToDatabaseQueryTool() {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(toolRegistry.executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"),
            org.mockito.ArgumentMatchers.any(ToolInput.class))).thenReturn(ToolOutput.success(Map.of("ok", true)));
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-dm");
        config.setToolName("db_query_dm");
        config.setTitle("DM query");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate("SELECT 1");
        config.setMaxRows(1);
        config.setTimeoutSeconds(90);

        ToolOutput output = service.invoke(config, Map.of());

        ArgumentCaptor<ToolInput> inputCaptor = ArgumentCaptor.forClass(ToolInput.class);
        verify(toolRegistry).executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"), inputCaptor.capture());
        Map<String, Object> parameters = inputCaptor.getValue().getParameters();
        assertThat(output.isSuccess()).isTrue();
        assertThat(parameters).containsEntry("database_type", "dm");
        assertThat(parameters).containsEntry("driver_class", "dm.jdbc.driver.DmDriver");
        assertThat(parameters).containsEntry("jdbc_url", "jdbc:dm://192.168.195.221:5236");
        assertThat(parameters).containsEntry("datasource_id", "asset-dm");
        assertThat(parameters).containsEntry("timeoutSeconds", 90);
        assertThat(parameters).containsEntry("timeout_seconds", 90);
    }

    @Test
    void injectsDynamicTradeDateParametersBeforeCallingDatabaseQueryTool() {
        DynamicDateParamService dateParamService = mock(DynamicDateParamService.class);
        DatabaseQueryInvokeService localService = new DatabaseQueryInvokeService(
            toolRegistry,
            datasourceConfigService,
            scriptExecuteService,
            dateParamService,
            new ObjectMapper(),
            auditService,
            cacheService
        );
        SqlDatasourceConfig datasource = dmDatasource();
        String sql = "SELECT * FROM customer_asset WHERE stat_date = :trade_date";
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(datasource);
        when(dateParamService.enrichParameters(anyMap(), eq(datasource), eq(sql)))
            .thenReturn(Map.of("trade_date", "20260707", "month", "202607", "natural_date", "20260707"));
        when(dateParamService.resolveSqlPlaceholders(eq(sql), eq(datasource))).thenReturn(sql);
        when(toolRegistry.executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"),
            org.mockito.ArgumentMatchers.any(ToolInput.class))).thenReturn(ToolOutput.success(Map.of("ok", true)));
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-asset");
        config.setToolName("customer_asset_query");
        config.setTitle("Customer asset query");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate(sql);
        config.setMaxRows(100);
        config.setTimeoutSeconds(30);

        ToolOutput output = localService.invoke(config, Map.of());

        ArgumentCaptor<ToolInput> inputCaptor = ArgumentCaptor.forClass(ToolInput.class);
        verify(toolRegistry).executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"), inputCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) inputCaptor.getValue().getParameters().get("params");
        assertThat(output.isSuccess()).isTrue();
        assertThat(inputCaptor.getValue().getParameters()).containsEntry("sql", sql);
        assertThat(params)
            .containsEntry("trade_date", "20260707")
            .containsEntry("month", "202607")
            .containsEntry("natural_date", "20260707");
    }

    @Test
    void mapsConfiguredTradingDateSourceToBusinessParameterName() throws Exception {
        DynamicDateParamService dateParamService = mock(DynamicDateParamService.class);
        DatabaseQueryInvokeService localService = new DatabaseQueryInvokeService(
            toolRegistry,
            datasourceConfigService,
            scriptExecuteService,
            dateParamService,
            new ObjectMapper(),
            auditService,
            cacheService
        );
        SqlDatasourceConfig datasource = dmDatasource();
        String sql = "SELECT * FROM ORDERS WHERE BUSI_DATE = :busi_date";
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(datasource);
        when(dateParamService.resolveTokenForSource(eq(datasource),
            org.mockito.ArgumentMatchers.isNull(), eq("trade_date"))).thenReturn("20260720");
        when(dateParamService.enrichParameters(anyMap(), eq(datasource), eq(sql)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(dateParamService.resolveSqlPlaceholders(eq(sql), eq(datasource))).thenReturn(sql);
        when(toolRegistry.executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"),
            org.mockito.ArgumentMatchers.any(ToolInput.class))).thenReturn(ToolOutput.success(Map.of("rows", List.of())));

        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-business-date");
        config.setToolName("query_business_date");
        config.setTitle("Business date query");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate(sql);
        config.setInputSchemaJson("""
            {"type":"object","properties":{"busi_date":{"type":"string","defaultSource":"trade_date"}}}
            """);
        config.setMaxRows(10);
        config.setTimeoutSeconds(30);

        ToolOutput output = localService.invoke(config, Map.of());

        ArgumentCaptor<ToolInput> inputCaptor = ArgumentCaptor.forClass(ToolInput.class);
        verify(toolRegistry).executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"), inputCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) inputCaptor.getValue().getParameters().get("params");
        assertThat(output.isSuccess()).isTrue();
        assertThat(params).containsEntry("busi_date", "20260720");
    }

    @Test
    void invokesJsonDslDatabaseQueryStepsAndContinuesAfterOptionalFailure() {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(toolRegistry.executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"),
            org.mockito.ArgumentMatchers.any(ToolInput.class))).thenAnswer(invocation -> {
                ToolInput input = invocation.getArgument(1);
                String sql = String.valueOf(input.getParameters().get("sql"));
                if (sql.contains("MISSING_TABLE")) {
                    return ToolOutput.failure("table not found");
                }
                return ToolOutput.success(Map.of(
                    "sql", sql,
                    "columns", java.util.List.of("CNT"),
                    "rows", java.util.List.of(Map.of("CNT", 1)),
                    "rowCount", 1
                ));
            });
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-dsl");
        config.setToolName("db_query_dsl");
        config.setTitle("DB query DSL");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate("""
            {
              "templateCode": "DM_INSTANCE_STATUS",
              "templateType": "DB_SQL",
              "targetType": "DM",
              "continueOnError": true,
              "steps": [
                {
                  "stepCode": "OPTIONAL_LOCK",
                  "stepName": "Optional lock table",
                  "stepType": "SQL",
                  "required": false,
                  "sql": "SELECT * FROM MISSING_TABLE",
                  "analysisHint": "Optional lock evidence."
                },
                {
                  "stepCode": "SESSION_STAT",
                  "stepName": "Session statistics",
                  "stepType": "SQL",
                  "required": true,
                  "sql": "SELECT COUNT(*) CNT FROM V$SESSIONS",
                  "analysisHint": "Session count evidence."
                }
              ],
              "analysisPolicy": {
                "evidenceRequired": true
              }
            }
            """);
        config.setMaxRows(10);
        config.setTimeoutSeconds(30);

        ToolOutput output = service.invoke(config, Map.of());

        assertThat(output.isSuccess()).isTrue();
        Map<?, ?> data = (Map<?, ?>) output.getData();
        assertThat(data.get("mode")).isEqualTo("agent_runtime_template_dsl");
        assertThat(data.get("templateDsl").toString()).contains("DM_INSTANCE_STATUS", "SESSION_STAT");
        assertThat(data.get("results").toString()).contains("OPTIONAL_LOCK", "Session statistics", "analysisHint");
    }

    @Test
    void invokesConfiguredSqlStepsSequentiallyAndReturnsResultSetDescriptions() throws Exception {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(toolRegistry.executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"),
            org.mockito.ArgumentMatchers.any(ToolInput.class))).thenAnswer(invocation -> {
                ToolInput input = invocation.getArgument(1);
                String sql = String.valueOf(input.getParameters().get("sql"));
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) input.getParameters().get("params");
                return ToolOutput.success(Map.of(
                    "sql", sql,
                    "columns", List.of("NAME", "VALUE"),
                    "rows", List.of(Map.of("NAME", params.getOrDefault("status", "all"), "VALUE", 1)),
                    "rowCount", 1
                ));
            });
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-multi");
        config.setToolName("db_query_multi");
        config.setTitle("Multi SQL query");
        config.setDescription("Template level description");
        config.setImplementationSteps("First read summary, then read active detail.");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate("SELECT 1");
        config.setMaxRows(20);
        config.setTimeoutSeconds(30);
        config.setSqlStepsJson(new ObjectMapper().writeValueAsString(List.of(
            sqlStep("SUMMARY", "Summary result", "SELECT COUNT(*) CNT FROM ORDERS", 1, null),
            sqlStep("ACTIVE_DETAIL", "Active detail rows", "SELECT * FROM ORDERS WHERE STATUS = :status", 2,
                Map.of("status", "ACTIVE"))
        )));

        ToolOutput output = service.invoke(config, Map.of("tenantId", "t1"));

        assertThat(output.isSuccess()).isTrue();
        Map<?, ?> data = (Map<?, ?>) output.getData();
        assertThat(data.get("mode")).isEqualTo("database_query_multi_sql");
        assertThat(data.get("executionMode")).isEqualTo("SEQUENTIAL");
        assertThat(data.get("resultSetCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultSets = (List<Map<String, Object>>) data.get("resultSets");
        assertThat(resultSets)
            .extracting(item -> item.get("sqlCode"))
            .containsExactly("SUMMARY", "ACTIVE_DETAIL");
        assertThat(resultSets)
            .extracting(item -> item.get("resultSetDescription"))
            .containsExactly("Summary result", "Active detail rows");

        ArgumentCaptor<ToolInput> inputCaptor = ArgumentCaptor.forClass(ToolInput.class);
        verify(toolRegistry, org.mockito.Mockito.times(2))
            .executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"), inputCaptor.capture());
        assertThat(inputCaptor.getAllValues())
            .extracting(input -> input.getParameters().get("sql"))
            .containsExactly("SELECT COUNT(*) CNT FROM ORDERS", "SELECT * FROM ORDERS WHERE STATUS = :status");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondParams =
            (Map<String, Object>) inputCaptor.getAllValues().get(1).getParameters().get("params");
        assertThat(secondParams).containsEntry("status", "ACTIVE");
    }

    @Test
    void keepsIntermediateSqlRowsAvailableForAdministrationPreview() throws Exception {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(toolRegistry.executeEnhancedTool(eq("database_query"), org.mockito.ArgumentMatchers.any(ToolInput.class)))
            .thenReturn(ToolOutput.success(Map.of(
                "columns", List.of("ID"),
                "rows", List.of(Map.of("ID", 1)),
                "rowCount", 1
            )));

        DatabaseQuerySqlStep intermediate = sqlStep("SQL_1", "Intermediate", "SELECT 1 ID", 1, null);
        intermediate.setReturnToModel(false);
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-preview-intermediate");
        config.setToolName("query_preview_intermediate");
        config.setTitle("Preview intermediate SQL");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate("SELECT 1 ID");
        config.setMaxRows(50);
        config.setTimeoutSeconds(30);
        config.setSqlStepsJson(new ObjectMapper().writeValueAsString(List.of(intermediate)));

        ToolOutput output = service.invokePreview(config, Map.of());

        assertThat(output.isSuccess()).isTrue();
        Map<?, ?> data = (Map<?, ?>) output.getData();
        assertThat((List<?>) data.get("resultSets")).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewResultSets = (List<Map<String, Object>>) data.get("previewResultSets");
        assertThat(previewResultSets).hasSize(1);
        assertThat(previewResultSets.get(0))
            .containsEntry("sqlCode", "SQL_1")
            .containsEntry("rowCount", 1);
    }

    @Test
    void returnsWorkflowFailureWithoutCrashingWhenDraftMetadataAndToolErrorAreEmpty() throws Exception {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(toolRegistry.executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"),
            org.mockito.ArgumentMatchers.any(ToolInput.class))).thenReturn(ToolOutput.builder()
                .success(false)
                .exceptionType("DataAccessException")
                .build());

        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("draft");
        config.setToolName("draft_database_query");
        config.setTitle("Draft database query");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate("SELECT * FROM ORDERS WHERE STATUS = :status");
        config.setMaxRows(50);
        config.setTimeoutSeconds(30);
        config.setSqlStepsJson(new ObjectMapper().writeValueAsString(List.of(
            sqlStep("SQL_1", "Draft step", "SELECT * FROM ORDERS WHERE STATUS = :status", 1, null)
        )));

        ToolOutput output = service.invoke(config, Map.of("status", "ACTIVE"));

        assertThat(output.isSuccess()).isFalse();
        assertThat(output.getErrorMessage()).isEqualTo("Database query execution failed (DataAccessException)");
        assertThat(output.getData()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) output.getData();
        assertThat(data.get("tool")).isEqualTo(Map.of(
            "name", "draft_database_query",
            "title", "Draft database query",
            "description", "",
            "implementationSteps", ""
        ));
        assertThat(data.get("failedResultSetCount")).isEqualTo(1);
    }

    private DatabaseQuerySqlStep sqlStep(String code,
                                         String description,
                                         String sql,
                                         int executionOrder,
                                         Map<String, Object> parameters) {
        DatabaseQuerySqlStep step = new DatabaseQuerySqlStep();
        step.setSqlCode(code);
        step.setSqlName(code);
        step.setSqlDescription(description);
        step.setSqlContent(sql);
        step.setExecutionOrder(executionOrder);
        step.setEnabled(true);
        step.setFailureStrategy("STOP");
        step.setParameters(parameters);
        return step;
    }

    private SqlDatasourceConfig dmDatasource() {
        SqlDatasourceConfig config = new SqlDatasourceConfig();
        config.setId("asset-dm");
        config.setName("DM datasource");
        config.setEnabled(true);
        config.setJdbcUrl("jdbc:dm://192.168.195.221:5236");
        config.setDriverClass("dm.jdbc.driver.DmDriver");
        config.setDatabaseType("dm");
        config.setUsername("GDP");
        config.setPassword("secret");
        return config;
    }
}
