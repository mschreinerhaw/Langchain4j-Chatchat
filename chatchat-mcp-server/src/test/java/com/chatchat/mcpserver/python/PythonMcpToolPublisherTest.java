package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonMcpToolPublisherTest {
    @Test
    void refreshRemovesLegacyTemplateToolsAndPublishesOnlyThreeProtocolTools() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> servers = mock(ObjectProvider.class);
        when(servers.getIfAvailable()).thenReturn(server);
        PythonTemplateAssetRepository templates = mock(PythonTemplateAssetRepository.class);
        PythonTemplate legacy = new PythonTemplate();
        legacy.setToolName("python_direct_template_1234");
        when(templates.findByStatus("PUBLISHED")).thenReturn(List.of(legacy));
        @SuppressWarnings("unchecked") ObjectProvider<PythonCapabilityService> services = mock(ObjectProvider.class);
        McpToolConcurrencyManager concurrency = mock(McpToolConcurrencyManager.class);
        when(concurrency.limitMeta(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Map.of());
        ObjectMapper objectMapper = new ObjectMapper();
        PythonMcpToolPublisher publisher = new PythonMcpToolPublisher(servers, templates,
            mock(PythonEnvironmentRepository.class), services, new PythonTemplateArgumentResolver(objectMapper),
            concurrency, objectMapper);

        publisher.refresh();

        verify(server).removeTool("python_direct_template_1234");
        verify(server).addTool(argThat(spec -> PythonMcpToolPublisher.ASSET_QUERY_TOOL.equals(spec.tool().name())));
        verify(server).addTool(argThat(spec -> PythonMcpToolPublisher.TEMPLATE_QUERY_TOOL.equals(spec.tool().name())));
        verify(server).addTool(argThat(spec -> PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL.equals(spec.tool().name())));
        verify(server, times(3)).addTool(org.mockito.ArgumentMatchers.any());
        verify(server).notifyToolsListChanged();
    }
}
