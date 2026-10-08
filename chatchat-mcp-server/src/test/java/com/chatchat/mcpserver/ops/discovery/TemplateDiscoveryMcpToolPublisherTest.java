package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.mcpserver.routing.target.TargetKindRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TemplateDiscoveryMcpToolPublisherTest {

    @Test
    void databaseParentsPublishOnlyForChildrenAndRejectDirectGlobalQueries() {
        var discovery = mock(CommandTemplateDiscoveryService.class);
        var children = mock(com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher.class);
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(children);
        var server = mock(McpSyncServer.class);
        var publisher = new TemplateDiscoveryMcpToolPublisher(server, discovery, new TargetKindRegistry(), provider);
        assertThat(publisher.contribute()).noneMatch(publication -> publication.toolName().contains("query_template_query"));
        org.mockito.Mockito.when(children.hasPublishedChildren("neo4j_query_template_query")).thenReturn(true);
        var parent = publisher.contribute().stream().filter(publication -> publication.toolName().equals("neo4j_query_template_query")).findFirst().orElseThrow();
        publisher.refresh();
        assertThat(parent.specification().tool().meta()).containsEntry("queryFamily", "neo4j").containsEntry("agentSelectable", false);
        var direct = parent.specification().callHandler().apply(null, new McpSchema.CallToolRequest("neo4j_query_template_query", Map.of("filters", Map.of()), Map.of()));
        assertThat(direct.isError()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(discovery);
        org.mockito.Mockito.when(children.hasPublishedChildren("neo4j_query_template_query")).thenReturn(false);
        assertThat(publisher.contribute()).noneMatch(publication -> publication.toolName().equals("neo4j_query_template_query"));
        publisher.refresh();
        verify(server).removeTool("neo4j_query_template_query");
    }

    @Test
    void sshTemplateToolIsTypedReadOnlyDiscoveryTool() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = publisher(mock(McpSyncServer.class));
        Method method = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "domainTemplateQueryTool", String.class, String.class, String.class,
            String.class, String.class, String.class);
        method.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) method.invoke(
                publisher,
                TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME,
                "SSH command template discovery",
                "Read-only MCP tool for retrieving SSH host command templates only.",
                "ssh_host",
                "host",
                "host command templates"
            );
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> boundary = (Map<?, ?>) meta.get("toolBoundary");
        Map<?, ?> indexPolicy = (Map<?, ?>) meta.get("indexPolicy");
        Map<?, ?> routingProtocol = (Map<?, ?>) meta.get("routingProtocol");

        assertThat(tool.name()).isEqualTo(TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME);
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("assetType")).isEqualTo("ssh_host");
        assertThat(meta.get("rawExecutionSpecReturned")).isEqualTo(false);
        assertThat(boundary.get("rejectCrossTypeRouting")).isEqualTo(true);
        assertThat(indexPolicy.get("logicalIndex")).isEqualTo("template:ssh_host");
        assertThat(((List<?>) routingProtocol.get("allowedFilterFields"))
            .stream().map(String::valueOf).toList())
            .contains("env", "intent", "retrievalsignals")
            .doesNotContain("templateids");
    }

    @Test
    void databaseQueryTemplateToolIsTypedCategoryDiscoveryTool() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = publisher(mock(McpSyncServer.class));
        Method method = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod(
            "domainTemplateQueryTool", String.class, String.class, String.class,
            String.class, String.class, String.class);
        method.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) method.invoke(
                publisher,
                TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME,
                "Categorized database query template discovery",
                "Searches published database query templates by data capability category.",
                "database_query",
                "business_database_query",
                "categorized database query template"
            );
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> applicability = (Map<?, ?>) meta.get("applicability");
        Map<?, ?> boundary = (Map<?, ?>) meta.get("toolBoundary");
        Map<?, ?> routingProtocol = (Map<?, ?>) meta.get("routingProtocol");

        assertThat(tool.name())
            .isEqualTo(TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME);
        assertThat(tool.title()).isEqualTo("Categorized database query template discovery");
        assertThat(tool.description().codePoints().allMatch(character -> character < 128)).isTrue();
        assertThat(meta.get("assetType")).isEqualTo("database_query");
        assertThat(meta.get("targetKind")).isEqualTo("business_database_query");
        assertThat(applicability.get("backendServiceTypes"))
            .isEqualTo(List.of("database_query", "template_discovery"));
        assertThat(boundary.get("rejectCrossTypeRouting")).isEqualTo(true);
        assertThat(routingProtocol.get("forcedTargetKind")).isEqualTo("business_database_query");
        assertThat(routingProtocol.get("categoryFirst")).isEqualTo(false);
        assertThat(routingProtocol.get("categoryUsage"))
            .isEqualTo("ranking_signal_and_model_selection_metadata");
        assertThat(routingProtocol.get("crossCategoryResultsAllowed")).isEqualTo(true);
        assertThat(meta.get("executionFlow").toString())
            .contains("FIXED_BINDING", "globalSearchPerformed=false");
        assertThat(meta.get("agentSelectable")).isEqualTo(false);
        assertThat(meta.get("defaultPublished")).isEqualTo(false);
        assertThat(meta.get("requiresChildBinding")).isEqualTo(true);
    }

    @Test
    void refreshLeavesDatabaseParentsUnpublishedWithoutChildrenAndRemovesLegacyTools() {
        McpSyncServer server = mock(McpSyncServer.class);
        org.mockito.Mockito.when(server.listTools()).thenReturn(List.of(McpSchema.Tool.builder()
            .name(TemplateDiscoveryMcpToolPublisher.JMX_TEMPLATE_TOOL_NAME).description("Legacy JMX toolbox").build()));
        TemplateDiscoveryMcpToolPublisher publisher = publisher(server);

        publisher.refresh();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> specifications =
            ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server, times(3)).addTool(specifications.capture());
        assertThat(specifications.getAllValues().stream().map(item -> item.tool().name()))
            .containsExactlyInAnyOrder(
                TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME,
                TemplateDiscoveryMcpToolPublisher.SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
                TemplateDiscoveryMcpToolPublisher.HTTP_ENDPOINT_TEMPLATE_TOOL_NAME);
        assertThat(specifications.getAllValues()).allSatisfy(spec ->
            assertThat((Map<?, ?>) spec.tool().inputSchema().get("properties"))
                .satisfies(properties -> assertThat(properties.containsKey(
                    com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT))
                    .isTrue()));
        verify(server).removeTool(TemplateDiscoveryMcpToolPublisher.JMX_TEMPLATE_TOOL_NAME);
        verify(server, never()).removeTool(TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME);
    }

    private TemplateDiscoveryMcpToolPublisher publisher(McpSyncServer server) {
        return new TemplateDiscoveryMcpToolPublisher(
            server,
            mock(CommandTemplateDiscoveryService.class),
            new TargetKindRegistry(),
            mock(org.springframework.beans.factory.ObjectProvider.class)
        );
    }
}
