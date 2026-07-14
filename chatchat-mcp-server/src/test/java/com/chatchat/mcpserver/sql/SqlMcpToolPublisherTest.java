package com.chatchat.mcpserver.sql;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.chatchat.tools.builtin.DatabaseToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlMcpToolPublisherTest {

    private final McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
    private final SqlScriptExecuteService scriptExecuteService = mock(SqlScriptExecuteService.class);
    private final SqlMetadataSearchService metadataSearchService = mock(SqlMetadataSearchService.class);
    private final DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
    private final DatabaseQueryInvokeService databaseQueryInvokeService = mock(DatabaseQueryInvokeService.class);
    private final ExecutionTargetRouter executionTargetRouter = mock(ExecutionTargetRouter.class);
    private final AssetMetadataFactory assetMetadataFactory = mock(AssetMetadataFactory.class);
    private final AgentRuntimeGovernanceFactory governanceFactory = mock(AgentRuntimeGovernanceFactory.class);
    private final McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);

    @Test
    @SuppressWarnings("unchecked")
    void sqlGatewayDelegatesBusinessQueryTemplateToDatabaseQueryExecutor() throws Exception {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-1");
        config.setToolName("query_edayQuqtMoni");
        config.setTitle("行情波动提醒");
        when(databaseQueryConfigService.listEnabled()).thenReturn(List.of(config));
        when(databaseQueryInvokeService.invoke(org.mockito.ArgumentMatchers.eq(config), org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(ToolOutput.success(Map.of("rows", List.of(Map.of("PD_CODE", "000001")))));

        SqlMcpToolPublisher publisher = new SqlMcpToolPublisher(
            mcpSyncServer,
            datasourceConfigService,
            sqlTemplateService,
            null,
            scriptExecuteService,
            metadataSearchService,
            databaseQueryConfigService,
            databaseQueryInvokeService,
            executionTargetRouter,
            assetMetadataFactory,
            governanceFactory,
            concurrencyManager,
            new StandardToolExecutionResultFactory(new DatabaseToolProperties()),
            new ChatChatMcpServerProperties(),
            new ObjectMapper()
        );

        Method method = SqlMcpToolPublisher.class.getDeclaredMethod("executeSqlGateway", Map.class);
        method.setAccessible(true);
        Object result = method.invoke(publisher, Map.of(
            "templateId", "query_edayQuqtMoni",
            "parameters", Map.of("limit", 10),
            "executionContext", Map.of("assetName", "达梦测试服务器", "env", "DEV")
        ));

        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(databaseQueryInvokeService).invoke(org.mockito.ArgumentMatchers.eq(config), argumentsCaptor.capture());
        verify(executionTargetRouter, never()).routeSqlQuery(org.mockito.ArgumentMatchers.anyMap());
        assertThat(argumentsCaptor.getValue()).containsEntry("limit", 10);
        assertThat(result).isNotNull();
    }

    @Test
    void sqlGatewayRoutesMultiStatementSqlToScriptExecutor() throws Exception {
        when(databaseQueryConfigService.listEnabled()).thenReturn(List.of());
        Map<String, Object> routed = new java.util.LinkedHashMap<>();
        routed.put("datasourceId", "ds-1");
        routed.put("sql", "select 1; select 2");
        routed.put("maxRows", 20);
        when(executionTargetRouter.routeSqlQuery(org.mockito.ArgumentMatchers.anyMap())).thenReturn(routed);
        when(scriptExecuteService.execute(org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(new SqlScriptResult(
                true,
                "ds-1",
                "db",
                "sql_db",
                "DEV",
                "select 1; select 2",
                5,
                20,
                2,
                List.of(),
                3,
                "analysis",
                "task-1",
                null,
                Map.of()
            ));

        SqlMcpToolPublisher publisher = new SqlMcpToolPublisher(
            mcpSyncServer,
            datasourceConfigService,
            sqlTemplateService,
            null,
            scriptExecuteService,
            metadataSearchService,
            databaseQueryConfigService,
            databaseQueryInvokeService,
            executionTargetRouter,
            assetMetadataFactory,
            governanceFactory,
            concurrencyManager,
            new StandardToolExecutionResultFactory(new DatabaseToolProperties()),
            new ChatChatMcpServerProperties(),
            new ObjectMapper()
        );

        Method method = SqlMcpToolPublisher.class.getDeclaredMethod("executeSqlGateway", Map.class);
        method.setAccessible(true);
        Object result = method.invoke(publisher, Map.of(
            "sql", "select 1; select 2",
            "executionContext", Map.of("assetName", "db", "env", "DEV")
        ));

        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(scriptExecuteService).execute(argumentsCaptor.capture());
        assertThat(argumentsCaptor.getValue())
            .containsEntry("script", "select 1; select 2")
            .containsEntry("maxRowsPerStatement", 20);
        assertThat(argumentsCaptor.getValue()).doesNotContainKey("sql");
        assertThat(result).isNotNull();
    }

    @Test
    void sqlGatewayRuntimeLevelUsesTemplateSqlWhenOnlyTemplateIdIsProvided() throws Exception {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-1");
        config.setToolName("query_market_overview_kpi_dashboard");
        config.setSqlTemplate("select 1 as total_count; select 2 as detail_count");
        when(databaseQueryConfigService.listEnabled()).thenReturn(List.of(config));
        when(scriptExecuteService.extractStatements(config.getSqlTemplate()))
            .thenReturn(List.of("select 1 as total_count", "select 2 as detail_count"));

        SqlMcpToolPublisher publisher = new SqlMcpToolPublisher(
            mcpSyncServer,
            datasourceConfigService,
            sqlTemplateService,
            null,
            scriptExecuteService,
            metadataSearchService,
            databaseQueryConfigService,
            databaseQueryInvokeService,
            executionTargetRouter,
            assetMetadataFactory,
            governanceFactory,
            concurrencyManager,
            new StandardToolExecutionResultFactory(new DatabaseToolProperties()),
            new ChatChatMcpServerProperties(),
            new ObjectMapper()
        );

        Method method = SqlMcpToolPublisher.class.getDeclaredMethod("sqlGatewayRuntimeLevel", Map.class);
        method.setAccessible(true);
        Object level = method.invoke(publisher, Map.of(
            "templateId", "query_market_overview_kpi_dashboard",
            "parameters", Map.of(),
            "executionContext", Map.of("assetName", "dm", "env", "DEV")
        ));

        assertThat(level).isEqualTo("sql_script");
    }

    @Test
    void metadataSearchSummarySupportsUnlimitedConfiguredLimits() throws Exception {
        ChatChatMcpServerProperties properties = new ChatChatMcpServerProperties();
        properties.getOutput().setSqlMetadataSearchSummaryMaxChars(-1);
        properties.getOutput().setSqlMetadataSearchSummaryMaxColumns(-1);
        SqlMcpToolPublisher publisher = new SqlMcpToolPublisher(
            mcpSyncServer,
            datasourceConfigService,
            sqlTemplateService,
            null,
            scriptExecuteService,
            metadataSearchService,
            databaseQueryConfigService,
            databaseQueryInvokeService,
            executionTargetRouter,
            assetMetadataFactory,
            governanceFactory,
            concurrencyManager,
            new StandardToolExecutionResultFactory(new DatabaseToolProperties()),
            properties,
            new ObjectMapper()
        );

        Method method = SqlMcpToolPublisher.class.getDeclaredMethod("summarizeMetadataSearchResult", Map.class);
        method.setAccessible(true);
        String summary = String.valueOf(method.invoke(publisher, Map.of(
            "results", List.of(Map.of(
                "location", Map.of("schema", "gdp_ads", "table", "ads_ids_secu_ast_liab_d_1"),
                "columnCount", 35,
                "columns", java.util.stream.IntStream.rangeClosed(1, 35)
                    .mapToObj(index -> Map.of(
                        "name", "COL_" + index,
                        "columnType", "varchar(32)",
                        "nullable", true,
                        "comment", "字段" + index
                    ))
                    .toList()
            ))
        )));

        assertThat(summary)
            .contains("`COL_35`")
            .doesNotContain("元数据摘要已截断")
            .doesNotContain("文本摘要仅展示前");
    }
}
