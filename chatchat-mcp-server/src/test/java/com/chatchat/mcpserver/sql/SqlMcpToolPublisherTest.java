package com.chatchat.mcpserver.sql;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
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
            metadataSearchService,
            databaseQueryConfigService,
            databaseQueryInvokeService,
            executionTargetRouter,
            assetMetadataFactory,
            governanceFactory,
            concurrencyManager,
            new StandardToolExecutionResultFactory(),
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
}
