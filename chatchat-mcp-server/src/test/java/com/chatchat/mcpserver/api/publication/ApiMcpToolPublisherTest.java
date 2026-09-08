package com.chatchat.mcpserver.api.publication;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMcpToolPublisherTest {

    @Test
    void refreshDoesNotPublishPerApiServiceTools() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        ApiServiceBridge bridge = mock(ApiServiceBridge.class);
        ApiToolSpecFactory toolSpecFactory = mock(ApiToolSpecFactory.class);
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification executor =
            mock(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        McpSchema.Tool executorTool = mock(McpSchema.Tool.class);
        when(executor.tool()).thenReturn(executorTool);
        when(executorTool.name()).thenReturn(ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        stubContract(executorTool);
        when(toolSpecFactory.toGatewayToolSpecification()).thenReturn(executor);
        McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);
        when(concurrencyManager.limitMeta(ApiMcpToolPublisher.BRIDGE_TOOL_NAME, "discovery")).thenReturn(java.util.Map.of());
        when(mcpSyncServer.listTools()).thenReturn(java.util.List.of());
        ApiMcpToolPublisher publisher = new ApiMcpToolPublisher(
            mcpSyncServer, bridge, toolSpecFactory, concurrencyManager, new ObjectMapper(),
            mock(org.springframework.beans.factory.ObjectProvider.class));

        publisher.refresh();

        verify(mcpSyncServer).addTool(org.mockito.ArgumentMatchers.argThat(specification ->
            ApiMcpToolPublisher.BRIDGE_TOOL_NAME.equals(specification.tool().name())));
        verify(mcpSyncServer).addTool(org.mockito.ArgumentMatchers.argThat(specification ->
            ApiMcpToolPublisher.EXECUTE_TOOL_NAME.equals(specification.tool().name())));
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bridgeSchemaDeclaresOnlyTheProtectedDynamicRoutingIdentity() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        ApiToolSpecFactory toolSpecFactory = mock(ApiToolSpecFactory.class);
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification executor =
            mock(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        McpSchema.Tool executorTool = mock(McpSchema.Tool.class);
        when(executor.tool()).thenReturn(executorTool);
        when(executorTool.name()).thenReturn(ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        stubContract(executorTool);
        when(toolSpecFactory.toGatewayToolSpecification()).thenReturn(executor);
        McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);
        when(concurrencyManager.limitMeta(ApiMcpToolPublisher.BRIDGE_TOOL_NAME, "discovery"))
            .thenReturn(Map.of());
        when(mcpSyncServer.listTools()).thenReturn(List.of());
        ApiMcpToolPublisher publisher = new ApiMcpToolPublisher(
            mcpSyncServer, mock(ApiServiceBridge.class), toolSpecFactory,
            concurrencyManager, new ObjectMapper(), mock(org.springframework.beans.factory.ObjectProvider.class));

        publisher.refresh();

        ArgumentCaptor<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> captor =
            ArgumentCaptor.forClass(
                io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        verify(mcpSyncServer, times(2)).addTool(captor.capture());
        McpSchema.Tool bridgeTool = captor.getAllValues().stream()
            .map(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification::tool)
            .filter(tool -> ApiMcpToolPublisher.BRIDGE_TOOL_NAME.equals(tool.name()))
            .findFirst().orElseThrow();
        Map<String, Object> properties =
            (Map<String, Object>) bridgeTool.inputSchema().get("properties");

        assertThat(properties).containsKeys(
            "filters", "trace", "limit", "assetType", "bilingualIntent", "intentZh", "intentEn",
            "purpose", "sourceTaskId");
        assertThat(properties).containsKey(TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT);
        assertThat(bridgeTool.inputSchema()).containsEntry("additionalProperties", false);
        assertThat(bridgeTool.meta())
            .containsEntry("runtimeLevel", "discovery")
            .containsEntry("runtime_level", "discovery");
    }

    @Test
    @SuppressWarnings("unchecked")
    void delegatesDynamicCapabilityToItsBoundQueryPolicy() {
        McpSyncServer server = mock(McpSyncServer.class);
        ApiToolSpecFactory toolSpecFactory = mock(ApiToolSpecFactory.class);
        var executor = mock(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        McpSchema.Tool executorTool = mock(McpSchema.Tool.class);
        when(executor.tool()).thenReturn(executorTool);
        when(executorTool.name()).thenReturn(ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        stubContract(executorTool);
        when(toolSpecFactory.toGatewayToolSpecification()).thenReturn(executor);
        McpToolConcurrencyManager concurrency = mock(McpToolConcurrencyManager.class);
        when(concurrency.limitMeta(ApiMcpToolPublisher.BRIDGE_TOOL_NAME, "discovery")).thenReturn(Map.of());
        when(concurrency.execute(anyString(), anyString(), anyMap(), any())).thenAnswer(invocation -> {
            Supplier<McpSchema.CallToolResult> operation = invocation.getArgument(3);
            return operation.get();
        });
        TemplateQueryMcpToolPublisher dynamicPublisher = mock(TemplateQueryMcpToolPublisher.class);
        when(dynamicPublisher.queryFromParent("tenant_template_query",
            ApiMcpToolPublisher.BRIDGE_TOOL_NAME, Map.of(
                TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT, "tenant_template_query", "limit", 5)))
            .thenReturn(Map.of("success", true, "templates", List.of()));
        org.springframework.beans.factory.ObjectProvider<TemplateQueryMcpToolPublisher> provider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(dynamicPublisher);
        ApiMcpToolPublisher publisher = new ApiMcpToolPublisher(server, mock(ApiServiceBridge.class),
            toolSpecFactory, concurrency, new ObjectMapper(), provider);

        var specification = publisher.contribute().stream()
            .map(com.chatchat.mcpserver.tool.ToolPublication::specification)
            .filter(item -> ApiMcpToolPublisher.BRIDGE_TOOL_NAME.equals(item.tool().name()))
            .findFirst().orElseThrow();
        assertThat(publisher.retiredToolNames())
            .doesNotContain(ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME);
        McpSchema.CallToolResult result = specification.callHandler().apply(null,
            new McpSchema.CallToolRequest(ApiMcpToolPublisher.BRIDGE_TOOL_NAME, Map.of(
                TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT, "tenant_template_query", "limit", 5), Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat((Map<String, Object>) result.structuredContent()).containsEntry("success", true);
        verify(dynamicPublisher).queryFromParent("tenant_template_query",
            ApiMcpToolPublisher.BRIDGE_TOOL_NAME, Map.of(
                TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT, "tenant_template_query", "limit", 5));
    }

    private static void stubContract(McpSchema.Tool tool) {
        when(tool.title()).thenReturn("API template execution");
        when(tool.description()).thenReturn("Execute one authorized API template");
        when(tool.inputSchema()).thenReturn(Map.of("type", "object", "properties", Map.of()));
    }
}
