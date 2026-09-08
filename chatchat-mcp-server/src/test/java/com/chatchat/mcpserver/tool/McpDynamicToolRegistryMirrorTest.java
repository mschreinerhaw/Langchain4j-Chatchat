package com.chatchat.mcpserver.tool;

import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.common.tool.ToolInput;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpDynamicToolRegistryMirrorTest {

    @Test
    void mirrorsMaterializedDescriptorAndCentralGovernanceIntoTheAgentRegistry() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        McpDynamicToolRegistryMirror mirror = new McpDynamicToolRegistryMirror(registry);
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("mirrored_query").title("Mirrored query").description("mirrored contract")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
            .meta(Map.of("schemaVersion", "mirrored.v2",
                "visibleTenantIds", List.of("tenant-a"))).build();
        ToolPublication publication = ToolPublication.from(
            McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                    .structuredContent(Map.of("ok", true)).isError(false).build())
                .build());

        McpDynamicToolRegistryMirror.publishIfAvailable(publication);

        assertThat(registry.getToolMetadata("mirrored_query").getSchemaVersion())
            .isEqualTo("mirrored.v2");
        assertThat(registry.executeEnhancedTool("mirrored_query", ToolInput.builder()
            .parameters(Map.of("tenantId", "tenant-b")).build()).isSuccess()).isFalse();
        assertThat(registry.executeEnhancedTool("mirrored_query", ToolInput.builder()
            .parameters(Map.of("tenantId", "tenant-a")).build()).isSuccess()).isTrue();

        McpDynamicToolRegistryMirror.unpublishIfAvailable("mirrored_query");
        assertThat(registry.hasTool("mirrored_query")).isFalse();
        mirror.close();
    }
}
