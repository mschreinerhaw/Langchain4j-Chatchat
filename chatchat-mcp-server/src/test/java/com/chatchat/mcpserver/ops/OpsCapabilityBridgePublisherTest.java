package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.routing.AssetDiscoveryService;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsCapabilityBridgePublisherTest {

    @Test
    void serverQueryCannotBeRedirectedToAnotherBusinessDomain() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(argThat(query -> "host".equals(query.get("finalDecision"))
            && "ssh_host".equals(query.get("assetType")))))
            .thenReturn(Map.of("returnedCount", 1));
        OpsCapabilityBridgePublisher publisher = publisher(discovery);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of("query", "inspect disk usage", "targetKind", "http", "assetType", "http_endpoint"));

        assertThat(result).containsEntry("businessDomain", "host")
            .containsEntry("assetType", "ssh_host")
            .containsEntry("executionTool", "linux_command_execute");
    }

    @Test
    void eachBusinessDomainReturnsItsOwnExecutor() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("templates", java.util.List.of()));
        OpsCapabilityBridgePublisher publisher = publisher(discovery);

        assertThat(publisher.query(OpsCapabilityBridgePublisher.HTTP_QUERY_TOOL, Map.of()))
            .containsEntry("businessDomain", "http").containsEntry("executionTool", "http_request_execute");
        assertThat(publisher.query(OpsCapabilityBridgePublisher.JMX_QUERY_TOOL, Map.of()))
            .containsEntry("businessDomain", "java").containsEntry("executionTool", "jmx_monitor_execute");
        assertThat(publisher.query(OpsCapabilityBridgePublisher.DATABASE_QUERY_TOOL, Map.of()))
            .containsEntry("businessDomain", "database").containsEntry("executionTool", "sql_query_execute");
    }

    @Test
    void jmxDoesNotPretendToSupportAssetDiscovery() {
        OpsCapabilityBridgePublisher publisher = publisher(mock(CommandTemplateDiscoveryService.class));

        assertThatThrownBy(() -> publisher.query(OpsCapabilityBridgePublisher.JMX_QUERY_TOOL,
            Map.of("stage", "asset")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("template discovery only");
    }

    @Test
    void refreshRemovesGenericBridgeAndPublishesDomainContracts() {
        McpSyncServer server = mock(McpSyncServer.class);
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            server, mock(AssetDiscoveryService.class), mock(CommandTemplateDiscoveryService.class));

        publisher.refresh();

        verify(server).removeTool(OpsCapabilityBridgePublisher.LEGACY_TOOL_NAME);
        verify(server).removeTool(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL);
        verify(server).removeTool(OpsCapabilityBridgePublisher.HTTP_QUERY_TOOL);
        verify(server).removeTool(OpsCapabilityBridgePublisher.JMX_QUERY_TOOL);
        verify(server).removeTool(OpsCapabilityBridgePublisher.DATABASE_QUERY_TOOL);
        verify(server).notifyToolsListChanged();
    }

    private OpsCapabilityBridgePublisher publisher(CommandTemplateDiscoveryService discovery) {
        return new OpsCapabilityBridgePublisher(mock(McpSyncServer.class), mock(AssetDiscoveryService.class), discovery);
    }
}
