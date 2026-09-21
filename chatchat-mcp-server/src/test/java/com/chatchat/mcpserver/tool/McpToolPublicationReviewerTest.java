package com.chatchat.mcpserver.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolPublicationReviewerTest {

    @Test
    void writesChineseAliasIntoPublishedMcpToolMetadata() {
        McpToolAliasRepository repository = mock(McpToolAliasRepository.class);
        when(repository.findById("name:api_template_execute"))
            .thenReturn(Optional.of(new McpToolAlias("name:api_template_execute", "API 模板执行")));
        McpToolChineseAliasResolver resolver = new McpToolChineseAliasResolver(repository, new ObjectMapper());
        try {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("api_template_execute")
            .title("API template execution gateway")
            .description("Execute an API template")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
            .build();
        McpServerFeatures.SyncToolSpecification pending = McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool).callHandler((exchange, request) -> null).build();

        McpToolPublicationReviewer.addReviewedTool(server, pending);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<McpServerFeatures.SyncToolSpecification> published =
            org.mockito.ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(published.capture());
        assertThat(published.getValue().tool().meta())
            .containsEntry("chineseAlias", "API 模板执行");
        } finally {
            resolver.close();
        }
    }

    @Test
    void preservesConfiguredAliasInPublishedMcpToolMetadata() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("customer_service_template_query")
            .title("Customer template query")
            .description("Query customer templates")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
            .meta(Map.of("chineseAlias", "自定义客户模板检索"))
            .build();
        McpServerFeatures.SyncToolSpecification pending = McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool).callHandler((exchange, request) -> null).build();

        McpToolPublicationReviewer.addReviewedTool(server, pending);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<McpServerFeatures.SyncToolSpecification> published =
            org.mockito.ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(published.capture());
        assertThat(published.getValue().tool().meta())
            .containsEntry("chineseAlias", "自定义客户模板检索");
    }

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
        when(pendingTool.title()).thenReturn("ambiguous api tool");
        when(pendingTool.description()).thenReturn("ambiguous api tool description");
        when(pendingTool.inputSchema()).thenReturn(Map.of("type", "object", "properties", Map.of()));

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
        when(pendingTool.title()).thenReturn("api template query");
        when(pendingTool.description()).thenReturn("query registered api templates");
        when(pendingTool.inputSchema()).thenReturn(Map.of("type", "object", "properties", Map.of()));

        McpToolPublicationReviewer.addReviewedTool(server, pending);

        verify(server).addTool(argThat(specification ->
            "api_template_query".equals(specification.tool().name())
                && "active".equals(specification.tool().meta().get("publicationStatus"))));
    }

    @Test
    void enrichesEveryDynamicallyPublishedHandlerFromRequestMeta() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("database_query")
            .title("database query")
            .description("query")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
            .build();
        Map<String, Object>[] capturedArguments = new Map[1];
        McpServerFeatures.SyncToolSpecification pending =
            McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    capturedArguments[0] = request.arguments();
                    return McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build();
                })
                .build();

        McpToolPublicationReviewer.addReviewedTool(server, pending);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<McpServerFeatures.SyncToolSpecification> published =
            org.mockito.ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(published.capture());
        published.getValue().callHandler().apply(null, new McpSchema.CallToolRequest(
            "database_query",
            Map.of("query", "select 1"),
            Map.of(
                "traceId", "request-1",
                "tenant", Map.of("tenantId", "tenant-1"),
                "user", Map.of("userId", "user-1")
            )
        ));

        assertThat(capturedArguments[0])
            .containsEntry("tenantId", "tenant-1")
            .containsEntry("userId", "user-1")
            .containsEntry("traceId", "request-1");
    }

    @Test
    void enforcesTenantVisibilityAtTheCentralPublicationBoundary() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("tenant_scoped_query").title("Tenant scoped query").description("tenant query")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
            .meta(Map.of("visibleTenantIds", List.of("tenant-a"))).build();
        McpServerFeatures.SyncToolSpecification pending = McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool).callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                .addTextContent("ok").isError(false).build()).build();

        McpToolPublicationReviewer.addReviewedTool(server, pending);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<McpServerFeatures.SyncToolSpecification> published =
            org.mockito.ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(published.capture());
        McpSchema.CallToolResult denied = published.getValue().callHandler().apply(null,
            new McpSchema.CallToolRequest("tenant_scoped_query", Map.of(),
                Map.of("tenant", Map.of("tenantId", "tenant-b"))));
        assertThat(denied.isError()).isTrue();
        assertThat(denied.structuredContent()).isEqualTo(Map.of(
            "success", false, "code", "MCP_TOOL_NOT_ENABLED_FOR_CALLER",
            "toolName", "tenant_scoped_query"));
    }

    @Test
    void requiresMigrationGuidanceForDeprecatedContracts() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("legacy_query").title("Legacy query").description("legacy query")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
            .meta(Map.of("publicationStatus", "deprecated")).build();
        McpServerFeatures.SyncToolSpecification pending = McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool).callHandler((exchange, request) -> null).build();

        assertThatThrownBy(() -> McpToolPublicationReviewer.review(ToolPublication.from(pending)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires migration guidance");
    }
}
