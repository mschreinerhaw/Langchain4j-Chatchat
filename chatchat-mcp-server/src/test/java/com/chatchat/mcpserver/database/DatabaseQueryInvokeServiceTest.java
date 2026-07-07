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
