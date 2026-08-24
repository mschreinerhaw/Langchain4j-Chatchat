package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class PythonMcpToolPublisherTest {
    @Test
    void publishesDiscoveryFacadeAndNativeRuntimeExecutor() {
        McpSyncServer server = mock(McpSyncServer.class);
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> servers = mock(ObjectProvider.class);
        when(servers.getIfAvailable()).thenReturn(server);
        PythonTemplateAssetRepository templates = mock(PythonTemplateAssetRepository.class);
        PythonTemplate legacy = new PythonTemplate();
        legacy.setToolName("python_direct_template_1234");
        when(templates.findByStatus("PUBLISHED")).thenReturn(List.of(legacy));
        PythonMcpToolPublisher publisher = new PythonMcpToolPublisher(servers, templates,
            mock(PythonAnalysisBridge.class), mock(McpToolConcurrencyManager.class), new ObjectMapper());

        publisher.refresh();

        verify(server).removeTool("python_direct_template_1234");
        for (String legacyTool : PythonMcpToolPublisher.LEGACY_PROTOCOL_TOOLS)
            verify(server).removeTool(legacyTool);
        verify(server).addTool(argThat(spec ->
            PythonMcpToolPublisher.ANALYSIS_RUN_TOOL.equals(spec.tool().name())
                && "Python analysis capability query".equals(spec.tool().title())));
        verify(server).addTool(argThat(spec ->
            PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL.equals(spec.tool().name())));
        verify(server, times(2)).addTool(org.mockito.ArgumentMatchers.any());
        verify(server).notifyToolsListChanged();
    }

    @Test
    void passesRequestMetaTenantToDynamicallyPublishedPythonTool() {
        McpSyncServer server = mock(McpSyncServer.class);
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> servers = mock(ObjectProvider.class);
        when(servers.getIfAvailable()).thenReturn(server);
        PythonTemplateAssetRepository templates = mock(PythonTemplateAssetRepository.class);
        when(templates.findByStatus("PUBLISHED")).thenReturn(List.of());
        PythonAnalysisBridge bridge = mock(PythonAnalysisBridge.class);
        when(bridge.run(anyMap())).thenReturn(new PythonAnalysisBridge.Result(
            Map.of("status", "CANDIDATES_FOUND"), false));
        McpToolConcurrencyManager concurrency = mock(McpToolConcurrencyManager.class);
        when(concurrency.execute(anyString(), anyString(), anyMap(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<McpSchema.CallToolResult> action = invocation.getArgument(3);
            return action.get();
        });
        PythonMcpToolPublisher publisher = new PythonMcpToolPublisher(
            servers, templates, bridge, concurrency, new ObjectMapper());
        publisher.refresh();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> specifications =
            ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server, times(2)).addTool(specifications.capture());
        McpServerFeatures.SyncToolSpecification analysis = specifications.getAllValues().stream()
            .filter(spec -> PythonMcpToolPublisher.ANALYSIS_RUN_TOOL.equals(spec.tool().name()))
            .findFirst()
            .orElseThrow();

        analysis.callHandler().apply(null, new McpSchema.CallToolRequest(
            PythonMcpToolPublisher.ANALYSIS_RUN_TOOL,
            Map.of("query", "log analysis"),
            Map.of(
                "traceId", "request-1",
                "tenant", Map.of("tenantId", "tenant-1"),
                "user", Map.of("userId", "user-1", "username", "admin", "roles", "role-a")
            )
        ));

        verify(bridge).run(argThat(arguments ->
            "tenant-1".equals(arguments.get("tenantId"))
                && "user-1".equals(arguments.get("userId"))
                && "request-1".equals(arguments.get("traceId"))));
    }
}
