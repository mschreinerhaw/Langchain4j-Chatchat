package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMcpToolPublisherTest {

    @Test
    void refreshDoesNotPublishPerApiServiceTools() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        ApiToolSpecFactory factory = mock(ApiToolSpecFactory.class);
        var specification = mock(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        when(factory.toGatewayToolSpecification()).thenReturn(specification);
        ApiMcpToolPublisher publisher = new ApiMcpToolPublisher(mcpSyncServer, factory);

        publisher.refresh();

        verify(mcpSyncServer).addTool(specification);
        verify(mcpSyncServer).notifyToolsListChanged();
    }
}
