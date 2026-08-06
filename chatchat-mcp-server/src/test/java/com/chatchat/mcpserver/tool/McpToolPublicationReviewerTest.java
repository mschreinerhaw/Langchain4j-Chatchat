package com.chatchat.mcpserver.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolPublicationReviewerTest {

    @Test
    void rejectsDynamicPublicationAmbiguousToExistingApiWorkflowName() {
        McpSyncServer server = mock(McpSyncServer.class);
        McpSchema.Tool existing = mock(McpSchema.Tool.class);
        when(existing.name()).thenReturn("api_template_query");
        when(server.listTools()).thenReturn(List.of(existing));

        McpServerFeatures.SyncToolSpecification pending = mock(McpServerFeatures.SyncToolSpecification.class);
        McpSchema.Tool pendingTool = mock(McpSchema.Tool.class);
        when(pending.tool()).thenReturn(pendingTool);
        when(pendingTool.name()).thenReturn("mcp_chatchat_mcp_server_api-template-query");

        assertThatThrownBy(() -> McpToolPublicationReviewer.addReviewedTool(server, pending))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ambiguous to workflow review");
        verify(server, never()).addTool(pending);
    }

    @Test
    void publishesNameThatPassesCurrentWorkflowReviewContract() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        McpServerFeatures.SyncToolSpecification pending = mock(McpServerFeatures.SyncToolSpecification.class);
        McpSchema.Tool pendingTool = mock(McpSchema.Tool.class);
        when(pending.tool()).thenReturn(pendingTool);
        when(pendingTool.name()).thenReturn("api_template_query");

        McpToolPublicationReviewer.addReviewedTool(server, pending);

        verify(server).addTool(pending);
    }
}
