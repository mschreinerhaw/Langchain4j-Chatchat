package com.chatchat.mcpserver.metadata;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MetadataGovernanceMcpToolPublisherTest {

    @Test
    void refreshPublishesAnnotationOnlyAndRemovesRetiredCompareTool() {
        McpSyncServer server = mock(McpSyncServer.class);
        MetadataGovernanceMcpToolPublisher publisher = new MetadataGovernanceMcpToolPublisher(
            server,
            mock(MetadataGovernanceAnalysisService.class),
            new EnterpriseMetadataProperties()
        );

        publisher.refresh();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> tool =
            ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).removeTool(MetadataGovernanceMcpToolPublisher.ANNOTATE_TOOL);
        verify(server).removeTool(MetadataGovernanceMcpToolPublisher.RETIRED_COMPARE_TOOL);
        verify(server).addTool(tool.capture());
        assertThat(tool.getValue().tool().name())
            .isEqualTo(MetadataGovernanceMcpToolPublisher.ANNOTATE_TOOL);
        verify(server).notifyToolsListChanged();
    }
}
