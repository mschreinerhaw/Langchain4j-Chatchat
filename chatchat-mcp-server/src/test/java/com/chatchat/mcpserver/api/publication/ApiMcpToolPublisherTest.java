package com.chatchat.mcpserver.api.publication;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMcpToolPublisherTest {

    @Test
    void refreshDoesNotPublishPerApiServiceTools() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        ApiServiceBridge bridge = mock(ApiServiceBridge.class);
        ApiToolSpecFactory toolSpecFactory = mock(ApiToolSpecFactory.class);
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification executor =
            mock(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        McpSchema.Tool executorTool = mock(McpSchema.Tool.class);
        when(executor.tool()).thenReturn(executorTool);
        when(executorTool.name()).thenReturn(ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        when(toolSpecFactory.toGatewayToolSpecification()).thenReturn(executor);
        McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);
        when(concurrencyManager.limitMeta(ApiMcpToolPublisher.BRIDGE_TOOL_NAME, "read_only")).thenReturn(java.util.Map.of());
        when(mcpSyncServer.listTools()).thenReturn(java.util.List.of());
        ApiMcpToolPublisher publisher = new ApiMcpToolPublisher(
            mcpSyncServer, bridge, toolSpecFactory, concurrencyManager, new ObjectMapper());

        publisher.refresh();

        verify(mcpSyncServer).addTool(org.mockito.ArgumentMatchers.argThat(specification ->
            ApiMcpToolPublisher.BRIDGE_TOOL_NAME.equals(specification.tool().name())));
        verify(mcpSyncServer).addTool(org.mockito.ArgumentMatchers.argThat(specification ->
            ApiMcpToolPublisher.EXECUTE_TOOL_NAME.equals(specification.tool().name())));
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bridgeSchemaAcceptsDelegatedDynamicTemplateQueryEnvelope() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        ApiToolSpecFactory toolSpecFactory = mock(ApiToolSpecFactory.class);
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification executor =
            mock(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        McpSchema.Tool executorTool = mock(McpSchema.Tool.class);
        when(executor.tool()).thenReturn(executorTool);
        when(executorTool.name()).thenReturn(ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        when(toolSpecFactory.toGatewayToolSpecification()).thenReturn(executor);
        McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);
        when(concurrencyManager.limitMeta(ApiMcpToolPublisher.BRIDGE_TOOL_NAME, "read_only"))
            .thenReturn(Map.of());
        when(mcpSyncServer.listTools()).thenReturn(List.of());
        ApiMcpToolPublisher publisher = new ApiMcpToolPublisher(
            mcpSyncServer, mock(ApiServiceBridge.class), toolSpecFactory,
            concurrencyManager, new ObjectMapper());

        publisher.refresh();

        ArgumentCaptor<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> captor =
            ArgumentCaptor.forClass(
                io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        verify(mcpSyncServer, times(2)).addTool(captor.capture());
        McpSchema.Tool bridgeTool = captor.getAllValues().stream()
            .map(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification::tool)
            .filter(tool -> ApiMcpToolPublisher.BRIDGE_TOOL_NAME.equals(tool.name()))
            .findFirst().orElseThrow();
        Map<String, Object> properties =
            (Map<String, Object>) bridgeTool.inputSchema().get("properties");

        assertThat(properties).containsKeys(
            "filters", "trace", "limit", "assetType", "bilingualIntent", "intentZh", "intentEn",
            TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT);
        assertThat(bridgeTool.inputSchema()).containsEntry("additionalProperties", false);
    }
}
