package com.chatchat.mcpserver.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class McpToolPublicationPipelineTest {

    @Test
    void overwritesNewContractsBeforeRemovingObsoleteTools() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        MutableContributor contributor = new MutableContributor(server,
            List.of(publication("tool_a", Map.of()), publication("tool_b", Map.of())));
        McpToolPublicationPipeline.publish(server, contributor);
        clearInvocations(server);

        contributor.publications = List.of(
            publication("tool_a", Map.of("revision", "2")),
            publication("tool_c", Map.of()));
        McpToolPublicationPipeline.publish(server, contributor);

        InOrder order = inOrder(server);
        order.verify(server).addTool(argThat(spec -> "tool_a".equals(spec.tool().name())));
        order.verify(server).addTool(argThat(spec -> "tool_c".equals(spec.tool().name())));
        order.verify(server).removeTool("tool_b");
        order.verify(server).notifyToolsListChanged();
    }

    @Test
    void blocksUnapprovedBreakingInputSchemaChangesBeforeMutation() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        MutableContributor contributor = new MutableContributor(server,
            List.of(publication("tool_a", Map.of())));
        McpToolPublicationPipeline.publish(server, contributor);
        clearInvocations(server);

        contributor.publications = List.of(publication("tool_a", Map.of(), List.of("newField")));

        assertThatThrownBy(() -> McpToolPublicationPipeline.publish(server, contributor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP_BREAKING_SCHEMA_CHANGE")
            .hasMessageContaining("required_added:newField");
        verify(server, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(server, never()).removeTool(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void restoresPreviousContractsWhenABatchAddFails() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        MutableContributor contributor = new MutableContributor(server,
            List.of(publication("tool_a", Map.of()), publication("tool_b", Map.of())));
        McpToolPublicationPipeline.publish(server, contributor);
        clearInvocations(server);
        doAnswer(invocation -> {
            McpServerFeatures.SyncToolSpecification specification = invocation.getArgument(0);
            if ("tool_c".equals(specification.tool().name())) {
                throw new IllegalStateException("registration failed");
            }
            return null;
        }).when(server).addTool(org.mockito.ArgumentMatchers.any());

        contributor.publications = List.of(
            publication("tool_a", Map.of("revision", "2")),
            publication("tool_b", Map.of()),
            publication("tool_c", Map.of()));

        assertThatThrownBy(() -> McpToolPublicationPipeline.publish(server, contributor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("registration failed");
        verify(server).addTool(argThat(spec -> "tool_a".equals(spec.tool().name())
            && !"2".equals(spec.tool().meta().get("revision"))));
        verify(server).removeTool("tool_c");
        verify(server, never()).notifyToolsListChanged();
    }

    @Test
    void lifecycleDisablementUnpublishesTheExistingTool() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        MutableContributor contributor = new MutableContributor(server,
            List.of(publication("tool_a", Map.of())));
        McpToolPublicationPipeline.publish(server, contributor);
        clearInvocations(server);

        contributor.publications = List.of(publication("tool_a",
            Map.of("publicationStatus", "disabled")));
        McpToolPublicationPipeline.publish(server, contributor);

        verify(server).removeTool("tool_a");
        verify(server, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(server).notifyToolsListChanged();
    }

    @Test
    void failedRemovalRemainsPendingAndIsRetriedOnTheNextRefresh() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        MutableContributor contributor = new MutableContributor(server,
            List.of(publication("tool_a", Map.of())));
        McpToolPublicationPipeline.publish(server, contributor);
        clearInvocations(server);
        doThrow(new IllegalStateException("registry busy"))
            .doNothing().when(server).removeTool("tool_a");
        contributor.publications = List.of(publication("tool_a",
            Map.of("publicationStatus", "disabled")));

        McpToolPublicationPipeline.publish(server, contributor);
        McpToolPublicationPipeline.publish(server, contributor);

        verify(server, times(2)).removeTool("tool_a");
        verify(server).notifyToolsListChanged();
    }

    @Test
    void skipsDeclaredRetirementsThatAreNotPresentInTheLiveRegistry() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        MutableContributor contributor = new MutableContributor(server, List.of());
        contributor.retired = Set.of("legacy_absent");

        McpToolPublicationPipeline.publish(server, contributor);

        verify(server, never()).removeTool("legacy_absent");
        verify(server, never()).notifyToolsListChanged();
    }

    @Test
    void removesDeclaredRetirementsThatArePresentInTheLiveRegistry() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of(publication("legacy_live", Map.of()).specification().tool()));
        MutableContributor contributor = new MutableContributor(server, List.of());
        contributor.retired = Set.of("legacy_live");

        McpToolPublicationPipeline.publish(server, contributor);

        verify(server).removeTool("legacy_live");
        verify(server).notifyToolsListChanged();
    }

    private static ToolPublication publication(String name, Map<String, Object> meta) {
        return publication(name, meta, List.of());
    }

    private static ToolPublication publication(String name, Map<String, Object> meta,
                                                List<String> required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("newField", Map.of("type", "string"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(name).title(name).description("contract for " + name)
            .inputSchema(new McpSchema.JsonSchema("object", properties, required, false, null, null))
            .meta(meta).build();
        McpServerFeatures.SyncToolSpecification specification =
            McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                    .addTextContent("ok").isError(false).build())
                .build();
        return ToolPublication.from(specification);
    }

    private static final class MutableContributor implements McpToolContributor {
        private final McpSyncServer server;
        private List<ToolPublication> publications;
        private Set<String> retired = Set.of();

        private MutableContributor(McpSyncServer server, List<ToolPublication> publications) {
            this.server = server;
            this.publications = publications;
        }

        @Override public String contributorId() { return "test_contributor"; }
        @Override public McpSyncServer publicationServer() { return server; }
        @Override public List<ToolPublication> contribute() { return publications; }
        @Override public Set<String> retiredToolNames() { return retired; }
    }
}
