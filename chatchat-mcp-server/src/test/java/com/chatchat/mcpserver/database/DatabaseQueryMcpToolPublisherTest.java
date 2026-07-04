package com.chatchat.mcpserver.database;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DatabaseQueryMcpToolPublisherTest {

    @Test
    void refreshDoesNotPublishPerDatabaseQueryTemplateTools() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        DatabaseQueryConfigService configService = mock(DatabaseQueryConfigService.class);
        DatabaseQueryMcpToolPublisher publisher = new DatabaseQueryMcpToolPublisher(
            mcpSyncServer,
            configService,
            mock(DatabaseQueryToolSpecFactory.class)
        );

        publisher.refresh();

        verify(mcpSyncServer, never()).removeTool(org.mockito.ArgumentMatchers.anyString());
        verify(mcpSyncServer, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }
}
