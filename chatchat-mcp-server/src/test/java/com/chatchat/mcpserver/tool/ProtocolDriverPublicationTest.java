package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.api.invocation.ApiInvokeService;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;
import com.chatchat.mcpserver.api.publication.ApiToolSpecFactory;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.execution.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.http.HttpRequestToolService;
import com.chatchat.mcpserver.ops.jmx.JmxMonitorService;
import com.chatchat.mcpserver.ops.jmx.JmxTemplateService;
import com.chatchat.mcpserver.ops.ssh.LinuxCommandService;
import com.chatchat.mcpserver.ops.tool.OpsMcpToolPublisher;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;
import com.chatchat.mcpserver.routing.AssetMetadataFactory;
import com.chatchat.mcpserver.routing.ExecutionTargetRouter;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.tool.SqlMcpToolPublisher;
import com.chatchat.mcpserver.sql.metadata.SqlMetadataSearchService;
import com.chatchat.mcpserver.sql.execution.SqlScriptExecuteService;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
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

        assertExecutionContract(meta(sql, "gatewayMeta"), "mcp.sql-template.v1");
        assertExecutionContract(meta(ops, "linuxCommandGatewayMeta"), "mcp.ssh-template.v1");
        assertExecutionContract(meta(ops, "httpRequestGatewayMeta"), "mcp.http-template.v1");
        assertExecutionContract(meta(api, "apiTemplateGatewayMeta"), "mcp.api-template.v1");
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

    @SuppressWarnings("unchecked")
    private void assertExecutionContract(Map<String, Object> metadata, String protocolFamily) {
        assertDriver(metadata, protocolFamily);
        assertThat(metadata).containsKey(ToolWorkflowContract.METADATA_KEY);
        Map<String, Object> workflow = (Map<String, Object>) metadata.get(ToolWorkflowContract.METADATA_KEY);
        assertThat(workflow)
            .containsEntry("schemaVersion", ToolWorkflowContract.SCHEMA_VERSION)
            .containsEntry("workflowRole", ToolWorkflowRole.TEMPLATE_EXECUTION.name())
            .containsEntry("protocolFamily", protocolFamily);
    }
}
