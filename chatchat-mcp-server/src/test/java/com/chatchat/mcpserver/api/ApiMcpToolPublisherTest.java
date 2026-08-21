package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
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
        verify(mcpSyncServer).addTool(executor);
        verify(mcpSyncServer).notifyToolsListChanged();
    }
}
