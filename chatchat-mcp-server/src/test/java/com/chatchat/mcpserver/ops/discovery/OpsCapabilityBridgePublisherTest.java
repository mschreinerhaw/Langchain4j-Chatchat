package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.mcpserver.routing.asset.AssetDiscoveryService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
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
            Map.of("query", "inspect two independent capabilities", "limit", 3,
                "filters", Map.of("assetName", "known-host")));

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
    void assetStageMapsBridgeQueryIntoRetrievalFiltersWithoutLeakingUnsupportedQueryField() {
        AssetDiscoveryService assets = mock(AssetDiscoveryService.class);
        when(assets.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("assets", java.util.List.of()));
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            mock(McpSyncServer.class), assets, mock(CommandTemplateDiscoveryService.class));

        publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL, Map.of(
            "stage", "asset", "query", "DEV Oracle server", "filters", Map.of("env", "DEV")));

        verify(assets).query(argThat(input -> !input.containsKey("query")
            && String.valueOf(((Map<?, ?>) input.get("filters")).get("intent")).contains("Oracle")));
    }

    @Test
    void templateStagePreResolvesDecisiveAssetBeforeLookingUpTemplates() {
        AssetDiscoveryService assets = mock(AssetDiscoveryService.class);
        CommandTemplateDiscoveryService templates = mock(CommandTemplateDiscoveryService.class);
        when(assets.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("assets", java.util.List.of(
            asset("oracle-host", "Oracle database server", "ssh_oracle", 0.82),
            asset("wind-app", "Wind application server", "ssh_wind", 0.41)
        )));
        when(templates.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("templates", java.util.List.of()));
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            mock(McpSyncServer.class), assets, templates);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of("query", "inspect the DEV Oracle host", "filters", Map.of("env", "DEV")));

        verify(assets).query(argThat(input -> {
            Object value = ((Map<?, ?>) input.get("filters")).get("queryTerms");
            if (!(value instanceof java.util.List<?> terms)) return false;
            java.util.List<String> normalizedTerms = terms.stream()
                .map(String::valueOf).map(term -> term.toLowerCase(java.util.Locale.ROOT)).toList();
            String identityIntent = String.valueOf(((Map<?, ?>) input.get("filters")).get("intent"))
                .toLowerCase(java.util.Locale.ROOT);
            return identityIntent.contains("oracle") && !identityIntent.equals("inspect the dev oracle host")
                && normalizedTerms.stream().anyMatch(term -> term.contains("oracle"))
                && normalizedTerms.stream().noneMatch("inspect the dev oracle host"::equals)
                && normalizedTerms.stream().noneMatch("dev"::equals);
        }));
        verify(templates).query(argThat(input -> "Oracle database server".equals(
            ((Map<?, ?>) input.get("filters")).get("assetName"))));
        assertThat(result.get("assetResolution").toString())
            .contains("RESOLVED", "Oracle database server", "oracle-host");
    }

    @Test
    void ambiguousAssetCandidatesDoNotSilentlyBindFirstRegistryResult() {
        AssetDiscoveryService assets = mock(AssetDiscoveryService.class);
        CommandTemplateDiscoveryService templates = mock(CommandTemplateDiscoveryService.class);
        when(assets.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("assets", java.util.List.of(
            asset("host-a", "Candidate A", "ssh_a", 0.70),
            asset("host-b", "Candidate B", "ssh_b", 0.64)
        )));
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            mock(McpSyncServer.class), assets, templates);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of("query", "inspect DEV host", "filters", Map.of("env", "DEV")));

        verify(templates, never()).query(org.mockito.ArgumentMatchers.anyMap());
        assertThat(result).containsEntry("stage", "asset_selection")
            .containsEntry("assetSelectionRequired", true)
            .containsEntry("assetReturnedCount", 2);
        assertThat(result.get("assetResolution").toString()).contains("AMBIGUOUS").doesNotContain("selected=");
    }

    @Test
    void uniqueCanonicalIdentityMatchResolvesEvenWhenSemanticScoresAreClose() {
        AssetDiscoveryService assets = mock(AssetDiscoveryService.class);
        CommandTemplateDiscoveryService templates = mock(CommandTemplateDiscoveryService.class);
        when(assets.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("assets", java.util.List.of(
            asset("oracle-db", "Wind Oracle datasource", "db_query_oracle_wind_dev", 0.82),
            asset("mysql-db", "Test database", "db_query_mysql_test", 0.757)
        )));
        when(templates.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("templates", java.util.List.of()));
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            mock(McpSyncServer.class), assets, templates);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.DATABASE_QUERY_TOOL,
            Map.of("query", "inspect DEV Oracle database", "filters", Map.of("env", "DEV")));

        verify(templates).query(argThat(input -> "Wind Oracle datasource".equals(
            ((Map<?, ?>) input.get("filters")).get("assetName"))));
        assertThat(result.get("assetResolution").toString())
            .contains("RESOLVED", "Wind Oracle datasource", "oracle-db");
    }

    @Test
    void missingAssetDoesNotFallBackToUnscopedTemplateDiscovery() {
        AssetDiscoveryService assets = mock(AssetDiscoveryService.class);
        CommandTemplateDiscoveryService templates = mock(CommandTemplateDiscoveryService.class);
        when(assets.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("assets", java.util.List.of()));
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            mock(McpSyncServer.class), assets, templates);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of("query", "inspect an unknown host", "filters", Map.of("env", "DEV")));

        verify(templates, never()).query(org.mockito.ArgumentMatchers.anyMap());
        assertThat(result).containsEntry("stage", "asset_selection")
            .containsEntry("assetNotFound", true)
            .containsEntry("assetReturnedCount", 0);
        assertThat(result).doesNotContainKeys("assetSelectionRequired", "nextStage");
        assertThat(result.get("assetResolution").toString()).contains("NOT_FOUND");
    }

    @Test
    void refreshRemovesGenericBridgeAndPublishesDomainContracts() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(java.util.List.of(McpSchema.Tool.builder()
            .name(OpsCapabilityBridgePublisher.LEGACY_TOOL_NAME)
            .description("legacy")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), java.util.List.of(), false, null, null))
            .build()));
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            server, mock(AssetDiscoveryService.class), mock(CommandTemplateDiscoveryService.class));

        publisher.refresh();

        verify(server).removeTool(OpsCapabilityBridgePublisher.LEGACY_TOOL_NAME);
        verify(server, org.mockito.Mockito.never()).removeTool(
            TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME);
        verify(server, org.mockito.Mockito.never()).removeTool(
            TemplateDiscoveryMcpToolPublisher.HTTP_ENDPOINT_TEMPLATE_TOOL_NAME);
        verify(server, org.mockito.Mockito.never()).removeTool(
            TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME);
        verify(server, org.mockito.Mockito.never()).removeTool(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL);
        verify(server, org.mockito.Mockito.never()).removeTool(OpsCapabilityBridgePublisher.HTTP_QUERY_TOOL);
        verify(server, org.mockito.Mockito.never()).removeTool(OpsCapabilityBridgePublisher.JMX_QUERY_TOOL);
        verify(server, org.mockito.Mockito.never()).removeTool(OpsCapabilityBridgePublisher.DATABASE_QUERY_TOOL);
        verify(server).notifyToolsListChanged();
    }

    @Test
    void serverBridgeDelegatesCustomQueryToPersistedSshParentPolicy() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        TemplateQueryMcpToolPublisher dynamic = mock(TemplateQueryMcpToolPublisher.class);
        when(dynamic.queryFromParent(org.mockito.ArgumentMatchers.eq("team_ops_template_query"),
            org.mockito.ArgumentMatchers.eq(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "templates", java.util.List.of(Map.of("templateId", "disk_check"))));
        OpsCapabilityBridgePublisher publisher = publisher(discovery);
        publisher.configureDynamicTemplateQueries(dynamic);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of(TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT, "team_ops_template_query"));

        assertThat(result.get("templates").toString()).contains("disk_check");
        verify(dynamic).queryFromParent(org.mockito.ArgumentMatchers.eq("team_ops_template_query"),
            org.mockito.ArgumentMatchers.eq(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL),
            org.mockito.ArgumentMatchers.anyMap());
        verifyNoInteractions(discovery);
    }

    private OpsCapabilityBridgePublisher publisher(CommandTemplateDiscoveryService discovery) {
        return new OpsCapabilityBridgePublisher(mock(McpSyncServer.class), mock(AssetDiscoveryService.class), discovery);
    }

    private Map<String, Object> asset(String id, String name, String toolName, double score) {
        return Map.of(
            "asset", Map.of("id", id, "name", name, "toolName", toolName, "environment", "DEV"),
            "routingHints", Map.of("assetSelection", Map.of("finalScore", score))
        );
    }
}
