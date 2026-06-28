package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ApiMcpToolPublisherTest {

    @Test
    void refreshDoesNotPublishPerApiServiceTools() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        ApiMcpToolPublisher publisher = new ApiMcpToolPublisher(mcpSyncServer);

        publisher.refresh();

        verify(mcpSyncServer, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }
}
