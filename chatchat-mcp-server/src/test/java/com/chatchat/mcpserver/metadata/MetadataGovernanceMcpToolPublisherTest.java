package com.chatchat.mcpserver.metadata;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MetadataGovernanceMcpToolPublisherTest {

    @Test
    void refreshRemovesRetiredMetadataToolsWithoutRepublishingThem() {
        McpSyncServer server = mock(McpSyncServer.class);
        MetadataGovernanceMcpToolPublisher publisher = new MetadataGovernanceMcpToolPublisher(
            server,
            new EnterpriseMetadataProperties()
        );

        publisher.refresh();

        verify(server).removeTool(MetadataGovernanceMcpToolPublisher.RETIRED_ANNOTATE_TOOL);
        verify(server).removeTool(MetadataGovernanceMcpToolPublisher.RETIRED_COMPARE_TOOL);
        verify(server, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(server).notifyToolsListChanged();
    }
}
