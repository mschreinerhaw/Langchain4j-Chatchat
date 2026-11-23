package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.mcpserver.routing.asset.AssetDiscoveryService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class OpsCapabilityBridgePublisherTest {
    @Test void discoveryGuidanceUsesTheActualQueryExecutionBudget() {
        var publisher=publisher(mock(CommandTemplateDiscoveryService.class));
        var budget=new com.chatchat.common.retrieval.query.QueryOptimizationBudget();budget.setMaxBatches(7);
        try(var optimizer=new com.chatchat.common.retrieval.query.SearchQueryOptimizer(budget,null)) {
            publisher.configureQueryBudget(optimizer);
            for(var publication:publisher.contribute()) {
                var metadata=publication.specification().tool().meta();
                assertThat(String.valueOf(metadata.get("modelInputBridgeContract")))
                    .contains("maximumBatches=7", "every keyword/alias channel");
            }
        }
    }

    @Test
    void publishedFilterVocabularyMatchesProviderAdmissionForEveryDomain() {
        var registry = new com.chatchat.mcpserver.routing.target.TargetKindRegistry();
        for (var publication : publisher(mock(CommandTemplateDiscoveryService.class)).contribute()) {
            var tool = publication.specification().tool();
            String kind = switch (tool.name()) {
                case OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL -> "host";
                case OpsCapabilityBridgePublisher.DATABASE_QUERY_TOOL -> "database";
                case OpsCapabilityBridgePublisher.HTTP_QUERY_TOOL -> "http";
                default -> "java";
            };
            var properties = (Map<?, ?>) tool.inputSchema().get("properties");
            var filters = (Map<?, ?>) properties.get("filters");
            assertThat(filters.get("additionalProperties")).isEqualTo(false);
            var fields = ((Map<?, ?>) filters.get("properties")).keySet().stream()
                .map(String::valueOf).map(name -> name.toLowerCase(java.util.Locale.ROOT).replace("_", ""))
                .collect(java.util.stream.Collectors.toSet());
            assertThat(fields).isEqualTo(registry.allowedTemplateFilterFieldsForTargetKind(kind).stream()
                .map(name -> name.replace("_", "")).collect(java.util.stream.Collectors.toSet()));
            assertThat(fields).contains("env", "queryterms", "intent").doesNotContain("description");
        }
    }

    @Test
    void explicitAssetSelectionContinuesTemplateStageWithoutRediscovery() {
        var assets = mock(AssetDiscoveryService.class);
        var templates = mock(CommandTemplateDiscoveryService.class);
        when(templates.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("templates", java.util.List.of()));
        var publisher = new OpsCapabilityBridgePublisher(mock(McpSyncServer.class), assets, templates);
        var ids = java.util.List.of("asset-A", "asset-B");
        assertThat(publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL,
            Map.of("stage", "template", "query", "inspect", "filters", Map.of("assetIds", ids, "env", "SCOPE"))))
            .containsEntry("stage", "template");
        verifyNoInteractions(assets);
        verify(templates).query(argThat(query -> ids.equals(((Map<?, ?>) query.get("filters")).get("assetIds"))));
    }

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
    void allDomainBridgesPublishAndForwardTheirTemplateContinuationCursor() {
        var discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("templates", java.util.List.of()));
        var publisher = publisher(discovery);
        for (var publication : publisher.contribute()) {
            assertThat(((Map<?, ?>) publication.specification().tool().inputSchema().get("properties"))
                .containsKey("cursor")).isTrue();
            var properties = (Map<?, ?>) publication.specification().tool().inputSchema().get("properties");
            var filters = (Map<?, ?>) properties.get("filters");
            assertThat(((Map<?, ?>) filters.get("properties")).containsKey("assetIds"))
                .isTrue();
        }
        publisher.query(OpsCapabilityBridgePublisher.SERVER_QUERY_TOOL, Map.of("stage", "template",
            "cursor", "opaque-next", "filters", Map.of("assetName", "authorized-host", "env", "SCOPE_A")));
        verify(discovery).query(argThat(input -> "opaque-next".equals(input.get("cursor"))
            && "authorized-host".equals(((Map<?, ?>) input.get("filters")).get("assetName"))));
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
                "filters", Map.of("assetName", "known-host", "intent", "Full task context; include a detailed report")));

        verify(discovery).query(argThat(query ->
            Integer.valueOf(CommandTemplateDiscoveryService.MAX_LIMIT).equals(query.get("limit"))
                && java.util.List.of("inspect two independent capabilities")
                    .equals(((Map<?, ?>) query.get("filters")).get("queryTerms"))));
        assertThat(result.get("candidateWindowPolicy").toString())
            .contains("FULL_BOUNDED_REVIEW_WINDOW", "runtimeOwned=true");
    }

    @Test
    void jmxCatalogUsesTheSameIdentityAndAuthorizationPathAsOtherDomains() {
        var assets = mock(AssetDiscoveryService.class);
        var templates = mock(CommandTemplateDiscoveryService.class);
        var publisher = new OpsCapabilityBridgePublisher(mock(McpSyncServer.class), assets, templates);
        var authorization = mock(com.chatchat.mcpserver.authorization.McpAuthorizationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "catalogAuthorization", authorization);
        java.util.function.Predicate<String> allowed = "JVM_RUNTIME"::equals;
        when(authorization.requireAssetCatalogAuthorization("jmx_monitor_execute", "jmx_endpoint")).thenReturn(allowed);
        when(assets.catalog(org.mockito.ArgumentMatchers.eq("jmx_endpoint"),
            org.mockito.ArgumentMatchers.eq("jmx_monitor_execute"), org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.same(allowed))).thenReturn(Map.of("authorizedAssetCount", 1));

        assertThat(publisher.query(OpsCapabilityBridgePublisher.JMX_QUERY_TOOL,
            Map.of("stage", "template", "catalogMode", "AUTHORIZED_CATALOG", "query", "jvm memory")))
            .containsEntry("stage", "asset_selection")
            .containsEntry("assetSelectionRequired", true)
            .containsEntry("authorizedAssetCount", 1);
        verifyNoInteractions(templates);
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
    void assetCandidatesRequireConfirmationRegardlessOfSearchRanking() {
        AssetDiscoveryService assets = mock(AssetDiscoveryService.class);
        CommandTemplateDiscoveryService templates = mock(CommandTemplateDiscoveryService.class);
        var candidates = java.util.List.of(asset("asset-a", "Candidate A", "tool_a", 0.82),
            asset("asset-b", "Candidate B", "tool_b", 0.41));
        when(assets.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("assets", candidates));
        OpsCapabilityBridgePublisher publisher = new OpsCapabilityBridgePublisher(
            mock(McpSyncServer.class), assets, templates);

        Map<String, Object> result = publisher.query(OpsCapabilityBridgePublisher.DATABASE_QUERY_TOOL,
            Map.of("filters", Map.of("queryTerms", java.util.List.of("Candidate A"), "env", "DEV")));

        verify(assets).query(argThat(input -> Map.of("queryTerms", java.util.List.of("Candidate A"), "env", "DEV")
            .equals(input.get("filters"))));
        verify(templates, never()).query(org.mockito.ArgumentMatchers.anyMap());
        assertThat(result).containsEntry("stage", "asset_selection")
            .containsEntry("assetSelectionRequired", true)
            .containsEntry("assetReturnedCount", 2)
            .containsEntry("assets", candidates);
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
