package com.chatchat.mcpserver.external;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalMcpToolPublisherTest {
    @Test void publishesOnlyApprovedReadOnlyTemplatesWithParentAndWorkflowMetadata() {
        ExternalMcpRegistryService registry = mock(ExternalMcpRegistryService.class);
        ExternalMcpService approved = new ExternalMcpService();
        approved.setId("6a550ebe-7cec-47b9-975a-3e413ca2e589");
        approved.setEnabled(true);
        approved.setParentToolName("api_service_template_query");
        approved.setWorkflowId("mcp_streamable_http");
        ExternalMcpService pending = new ExternalMcpService();
        pending.setId("pending");
        when(registry.list()).thenReturn(List.of(approved, pending));
        when(registry.templates(approved)).thenReturn(List.of(
            new ExternalMcpRegistryService.ToolTemplate("read/data", "Read", "Read data",
                Map.of("type", "object", "properties", Map.of()), true),
            new ExternalMcpRegistryService.ToolTemplate("write_data", "Write", "Write data",
                Map.of("type", "object", "properties", Map.of()), false)));
        when(registry.parentAssetType(approved)).thenReturn("api_service");

        ExternalMcpToolPublisher publisher = new ExternalMcpToolPublisher(mock(McpSyncServer.class), registry);
        var publications = publisher.contribute();

        assertThat(publications).hasSize(1);
        assertThat(publications.get(0).toolName()).startsWith("external_6a550ebe7cec47b9975a3e413ca2e589_read_data_");
        assertThat(publications.get(0).specification().tool().meta())
            .containsEntry("parentToolName", "api_service_template_query")
            .containsEntry("assetType", "api_service")
            .containsEntry("executionWorkflow", "mcp_streamable_http");
        assertThat(ExternalMcpToolPublisher.publishedName("service", "a/b"))
            .isNotEqualTo(ExternalMcpToolPublisher.publishedName("service", "a_b"));
    }
}
