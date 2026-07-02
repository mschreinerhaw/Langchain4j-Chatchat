package com.chatchat.mcpserver.database;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseQueryMcpToolPublisherTest {

    @Test
    void refreshDoesNotPublishPerDatabaseQueryTemplateTools() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        DatabaseQueryConfigService configService = mock(DatabaseQueryConfigService.class);
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName("query_edayQuqtMoni");
        when(configService.listEnabled()).thenReturn(List.of(config));
        DatabaseQueryMcpToolPublisher publisher = new DatabaseQueryMcpToolPublisher(
            mcpSyncServer,
            configService,
            mock(DatabaseQueryToolSpecFactory.class)
        );

        publisher.refresh();

        verify(mcpSyncServer).removeTool("database_query_execute");
        verify(mcpSyncServer).removeTool("query_edayQuqtMoni");
        verify(mcpSyncServer, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }
}
