package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.mcpserver.routing.AssetDiscoveryService;
import com.chatchat.mcpserver.templatepublication.TemplateQueryMcpToolPublisher;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void templateBridgeUsesFullBoundedCandidateWindowForRuntimeReview() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(Map.of("templates", java.util.List.of()));
        OpsCapabilityBridgePublisher publisher = publisher(discovery);

        Map<String, Object> result = publisher.query(
            OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of("query", "inspect two independent capabilities", "limit", 3));

        verify(discovery).query(argThat(query ->
            Integer.valueOf(CommandTemplateDiscoveryService.MAX_LIMIT).equals(query.get("limit"))));
        assertThat(result.get("candidateWindowPolicy").toString())
            .contains("FULL_BOUNDED_REVIEW_WINDOW", "runtimeOwned=true");
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

    @Test
    void serverBridgeDelegatesCustomQueryToPersistedSshParentPolicy() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        TemplateQueryMcpToolPublisher dynamic = mock(TemplateQueryMcpToolPublisher.class);
        when(dynamic.queryFromParent(org.mockito.ArgumentMatchers.eq("team_ops_template_query"),
            org.mockito.ArgumentMatchers.eq(TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "templates", java.util.List.of(Map.of("templateId", "disk_check"))));
        OpsCapabilityBridgePublisher publisher = publisher(discovery);
        publisher.configureDynamicTemplateQueries(dynamic);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of(TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT, "team_ops_template_query"));

        assertThat(result.get("templates").toString()).contains("disk_check");
        verify(dynamic).queryFromParent(org.mockito.ArgumentMatchers.eq("team_ops_template_query"),
            org.mockito.ArgumentMatchers.eq(TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME),
            org.mockito.ArgumentMatchers.anyMap());
        verifyNoInteractions(discovery);
    }

    private OpsCapabilityBridgePublisher publisher(CommandTemplateDiscoveryService discovery) {
        return new OpsCapabilityBridgePublisher(mock(McpSyncServer.class), mock(AssetDiscoveryService.class), discovery);
    }
}
