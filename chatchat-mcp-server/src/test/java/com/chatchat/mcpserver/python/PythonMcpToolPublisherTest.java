package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
