package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.mcpserver.api.ApiInvokeService;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.api.ApiToolSpecFactory;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.HttpRequestToolService;
import com.chatchat.mcpserver.ops.JmxMonitorService;
import com.chatchat.mcpserver.ops.JmxTemplateService;
import com.chatchat.mcpserver.ops.LinuxCommandService;
import com.chatchat.mcpserver.ops.OpsMcpToolPublisher;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlMcpToolPublisher;
import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import com.chatchat.mcpserver.sql.SqlScriptExecuteService;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.chatchat.tools.builtin.DatabaseToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtocolDriverPublicationTest {

    @Test
    void sqlHttpSshAndApiGatewaysPublishVersionedProtocolDrivers() throws Exception {
        AgentRuntimeGovernanceFactory governance = mock(AgentRuntimeGovernanceFactory.class);
        when(governance.toMeta(anyString(), anyString(), anyMap(), any())).thenReturn(Map.of());

        SqlMcpToolPublisher sql = new SqlMcpToolPublisher(
            mock(McpSyncServer.class), mock(SqlDatasourceConfigService.class), mock(SqlTemplateService.class),
            null, mock(SqlScriptExecuteService.class), mock(SqlMetadataSearchService.class),
            mock(DatabaseQueryConfigService.class), mock(DatabaseQueryInvokeService.class),
            mock(ExecutionTargetRouter.class), mock(AssetMetadataFactory.class), governance,
            mock(McpToolConcurrencyManager.class),
            new StandardToolExecutionResultFactory(new DatabaseToolProperties()),
            new ChatChatMcpServerProperties(), new ObjectMapper());
        OpsMcpToolPublisher ops = new OpsMcpToolPublisher(
            mock(McpSyncServer.class), mock(SshHostConfigService.class), mock(HttpEndpointConfigService.class),
            mock(HttpRequestToolService.class), mock(LinuxCommandService.class), mock(CommandTemplateService.class),
            mock(JmxTemplateService.class), mock(JmxMonitorService.class), mock(ExecutionTargetRouter.class),
            mock(AssetMetadataFactory.class), governance, mock(McpToolConcurrencyManager.class),
            mock(StandardToolExecutionResultFactory.class), new ObjectMapper());
        ApiToolSpecFactory api = new ApiToolSpecFactory(
            mock(ApiInvokeService.class), mock(ApiServiceConfigService.class), new ObjectMapper(), governance,
            mock(McpToolConcurrencyManager.class), mock(StandardToolExecutionResultFactory.class));

        assertDriver(meta(sql, "gatewayMeta"), "mcp.sql-template.v1");
        assertDriver(meta(sql, "scriptGatewayMeta"), "mcp.sql-template.v1");
        assertDriver(meta(ops, "linuxCommandGatewayMeta"), "mcp.ssh-template.v1");
        assertDriver(meta(ops, "httpRequestGatewayMeta"), "mcp.http-template.v1");
        assertDriver(meta(api, "apiTemplateGatewayMeta"), "mcp.api-template.v1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> meta(Object publisher, String methodName) throws Exception {
        Method method = publisher.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(publisher);
    }

    @SuppressWarnings("unchecked")
    private void assertDriver(Map<String, Object> metadata, String id) {
        assertThat(metadata).containsKey(ToolProtocolDriverContract.METADATA_KEY);
        Map<String, Object> driver = (Map<String, Object>) metadata.get(ToolProtocolDriverContract.METADATA_KEY);
        assertThat(driver)
            .containsEntry("schemaVersion", ToolProtocolDriverContract.SCHEMA_VERSION)
            .containsEntry("driverId", id);
        assertThat((java.util.List<String>) driver.get("plannerRules")).isNotEmpty();
        assertThat((java.util.List<String>) driver.get("rewriterRules")).isNotEmpty();
    }
}
