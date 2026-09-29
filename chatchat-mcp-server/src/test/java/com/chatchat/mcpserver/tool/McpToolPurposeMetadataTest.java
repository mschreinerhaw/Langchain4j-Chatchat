package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class McpToolPurposeMetadataTest {
    @Test void publishesPurposeInWireContractAndLocalDescriptor() {
        for (var entry : Map.of(ToolWorkflowRole.ASSET_DISCOVERY, "ASSET_QUERY",
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "TEMPLATE_QUERY",
            ToolWorkflowRole.TEMPLATE_EXECUTION, "ACTION_EXECUTION").entrySet()) {
            var tool = McpSchema.Tool.builder().name("opaque_tool").title("Opaque").description("Opaque")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
                .meta(Map.of("workflowContract", ToolWorkflowContract.declaration(entry.getKey(), "test", "query"))).build();
            var publication = ToolPublication.from(McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> null).build());
            assertThat(publication.descriptor().metadata().getDataType()).isEqualTo(entry.getValue());
            assertThat(McpToolPublicationReviewer.governedSpecification(publication).tool().meta())
                .containsEntry("data_type", entry.getValue());
        }
    }

    @Test void preservesExplicitAndExtensionPurposesAndLeavesUnknownUndeclaredToolsUnknown() {
        assertThat(McpToolPurposeMetadata.enrich(Map.of("data_type", "custom_purpose")))
            .containsEntry("data_type", "custom_purpose");
        assertThat(McpToolPurposeMetadata.enrich(Map.of("data_type", "DATA_FETCH", "workflowContract",
            ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_EXECUTION, "test", "query"))))
            .containsEntry("data_type", "DATA_FETCH");
        assertThat(McpToolPurposeMetadata.enrich(Map.of("name", "document_search")))
            .containsEntry("data_type", "UNKNOWN");
    }
}
