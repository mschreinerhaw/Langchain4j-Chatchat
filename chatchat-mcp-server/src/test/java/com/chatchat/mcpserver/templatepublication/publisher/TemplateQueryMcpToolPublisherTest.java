package com.chatchat.mcpserver.templatepublication.publisher;

import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryRouteResolver;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;

import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.mcp.capability.McpTemplateSelectionScope;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateQueryMcpToolPublisherTest {

    @Test
    void removesLegacyGenericTemplateQueryAndDoesNotPublishItAgain() {
        McpSyncServer server = mock(McpSyncServer.class);
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateQueryMcpToolPublisher publisher = new TemplateQueryMcpToolPublisher(
            server, bindings, bindings, mock(TemplateAssetCatalogService.class),
            new AgentRuntimeGovernanceFactory(new ObjectMapper()),
            mock(McpToolConcurrencyManager.class));
        when(bindings.publishedToolNames()).thenReturn(Set.of());

        publisher.refresh();

        verify(server).removeTool("template_query");
        verify(server, never()).addTool(org.mockito.ArgumentMatchers.any());
        verify(server).notifyToolsListChanged();
    }

    @Test
    void publishesFixedReviewedGovernanceWithoutEditableScopeArguments() {
        McpSyncServer server = mock(McpSyncServer.class);
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        when(server.listTools()).thenReturn(List.of());
        McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);
        when(concurrencyManager.limitMeta("customer_template_query", "discovery"))
            .thenReturn(Map.of("runtime_level", "discovery", "timeout_seconds", 90L));
        TemplateQueryMcpToolPublisher publisher = new TemplateQueryMcpToolPublisher(
            server, bindings, bindings, mock(TemplateAssetCatalogService.class),
            new AgentRuntimeGovernanceFactory(new ObjectMapper()),
            concurrencyManager);
        when(bindings.publishedToolNames()).thenReturn(Set.of("customer_template_query"));
        when(bindings.requireRoute("customer_template_query")).thenReturn(
            route("customer_template_query", "api_template_query", TemplateAssetCatalogService.API));

        publisher.refresh();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
            ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(captor.capture());
        assertThat(captor.getValue().tool().name()).isEqualTo("customer_template_query");
        assertThat(captor.getValue().tool().meta().toString())
            .contains("governanceEditable=false", "only_selected_templates=true", "allow_user_override=false",
                "routingMode=parent_delegation",
                "runtime_level=discovery", "timeout_seconds=90");
        assertThat(captor.getValue().tool().meta())
            .containsKey(McpDynamicCapabilityRoute.METADATA_KEY)
            .containsEntry("scopeMode", "FIXED_BINDING")
            .containsEntry("selectionMode", "BOUND_SCOPE_RECALL")
            .containsEntry("assetType", TemplateAssetCatalogService.API)
            .containsKey(McpTemplateSelectionScope.METADATA_KEY)
            .doesNotContainKeys("parentToolName", "kind");
        assertThat(McpDynamicCapabilityRoute.fromToolMetadata(captor.getValue().tool().meta()).orElseThrow())
            .satisfies(route -> {
                assertThat(route.parentToolName()).isEqualTo("api_template_query");
                assertThat(route.implementationIdentityArgument()).isEqualTo("_templateQueryChildToolName");
            });
        assertThat(McpTemplateSelectionScope.fromToolMetadata(
            captor.getValue().tool().meta()).orElseThrow())
            .satisfies(scope -> {
                assertThat(scope.assetType()).isEqualTo(TemplateAssetCatalogService.API);
                assertThat(scope.fixedBindingAuthority()).isTrue();
            });
        assertThat((Map<String, Object>) captor.getValue().tool().inputSchema().get("properties"))
            .doesNotContainKeys("templateIds", "serviceId", "roleId", "governance");

        var directResult = captor.getValue().callHandler().apply(null,
            new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                "customer_template_query", Map.of("limit", 10), Map.of()));
        assertThat(directResult.isError()).isTrue();
        assertThat((Map<String, Object>) directResult.structuredContent())
            .containsEntry("errorCode", "MCP_CHILD_CAPABILITY_REQUIRES_PARENT")
            .containsEntry("childToolName", "customer_template_query")
            .containsEntry("parentToolName", "api_template_query")
            .containsEntry("recoveryAction", "INVOKE_DECLARED_PARENT");
    }

    @Test
    void returnsNothingWhenServiceAndRoleHaveNoBinding() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, catalog);
        McpInvocationContext.Context context = context("service-1", "role-1");
        when(bindings.resolvePolicy(context, "customer_template_query")).thenReturn(policy(Map.of()));
        when(bindings.requireRoute("customer_template_query")).thenReturn(
            route("customer_template_query", "api_template_query", TemplateAssetCatalogService.API));
        when(catalog.listEnabled()).thenReturn(List.of());

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.query("customer_template_query", Map.of("assetType", "api_service", "limit", 20));
        }

        assertThat(result.get("templates")).isEqualTo(List.of());
        assertThat(result.toString()).contains("configuredTemplateCount=0");
        assertThat(result).containsEntry("globalSearchPerformed", false)
            .containsEntry("boundScopeRecallPerformed", true);
    }

    @Test
    void returnsTheFixedBoundTemplatesAndParameterContractsWithoutSearchingAgain() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, catalog);
        McpInvocationContext.Context context = context("service-1", "role-1");
        Set<String> allowed = Set.of("customer_query", "excluded_query");
        when(bindings.resolvePolicy(context, "customer_template_query"))
            .thenReturn(policy(Map.of("api_service", allowed)));
        when(bindings.requireRoute("customer_template_query")).thenReturn(
            route("customer_template_query", "api_template_query", TemplateAssetCatalogService.API));
        when(catalog.listEnabled()).thenReturn(List.of(
            asset(TemplateAssetCatalogService.API, "customer_query", Map.of("required", List.of("customer_id"))),
            asset(TemplateAssetCatalogService.API, "excluded_query", Map.of("type", "object")),
            asset(TemplateAssetCatalogService.API, "unbound_template", Map.of())));

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.queryFromParent("customer_template_query", "api_template_query", Map.of(
                "assetType", "api_service",
                "limit", 20
            ));
        }

        assertThat(result.get("templates").toString())
            .contains("customer_query", "excluded_query", "parameterSchema", "customer_id")
            .doesNotContain("unbound_template");
        assertThat(result).containsEntry("scopeMode", "FIXED_BINDING")
            .containsEntry("selectionMode", "BOUND_SCOPE_RECALL")
            .containsEntry("semanticReviewRequired", true)
            .containsEntry("globalSearchPerformed", false)
            .containsEntry("boundScopeRecallPerformed", true)
            .containsEntry("bindingComplete", true);
    }

    @Test
    void pagingDoesNotMarkTheCompleteBindingAsUnavailable() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, catalog);
        McpInvocationContext.Context context = context("service-1", "role-1");
        Set<String> allowed = Set.of("template-1", "template-2", "template-3");
        when(bindings.resolvePolicy(context, "customer_template_query"))
            .thenReturn(policy(Map.of("api_service", allowed)));
        when(bindings.requireRoute("customer_template_query")).thenReturn(
            route("customer_template_query", "api_template_query", TemplateAssetCatalogService.API));
        when(catalog.listEnabled()).thenReturn(List.of(
            asset(TemplateAssetCatalogService.API, "template-1", Map.of()),
            asset(TemplateAssetCatalogService.API, "template-2", Map.of()),
            asset(TemplateAssetCatalogService.API, "template-3", Map.of())));

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.queryFromParent("customer_template_query", "api_template_query",
                Map.of("limit", 1));
        }

        assertThat(result).containsEntry("returnedCount", 1)
            .containsEntry("candidateUniverseCount", 3)
            .containsEntry("hasMore", true)
            .containsEntry("bindingComplete", true);
        assertThat(result.get("filterAudit").toString())
            .contains("unavailableOrUnauthorizedCount=0");
    }

    @Test
    void resolvesPolicyFromInvocationArgumentsWhenTransportThreadContextIsLost() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, catalog);
        Map<String, Object> arguments = Map.of(
            "tenantId", "tenant-1",
            "userId", "user-1",
            "roles", "role-1",
            "limit", 10
        );
        Set<String> allowed = Set.of("customer_query");
        when(bindings.requireRoute("customer_template_query")).thenReturn(
            route("customer_template_query", "api_template_query", TemplateAssetCatalogService.API));
        when(bindings.resolvePolicy(null, "customer_template_query", arguments))
            .thenReturn(policy(Map.of("api_service", allowed)));
        when(catalog.listEnabled()).thenReturn(List.of(
            asset(TemplateAssetCatalogService.API, "customer_query", Map.of("type", "object"))));

        Map<String, Object> result = publisher.queryFromParent(
            "customer_template_query", "api_template_query", arguments);

        assertThat(result.get("templates").toString()).contains("customer_query");
        verify(bindings).resolvePolicy(null, "customer_template_query", arguments);
    }

    @Test
    void rejectsBridgeThatIsNotTheParentPersistedForTheChild() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateQueryMcpToolPublisher publisher = publisher(
            bindings, mock(TemplateAssetCatalogService.class));
        when(bindings.requireRoute("customer_template_query")).thenReturn(
            route("customer_template_query", "api_template_query", TemplateAssetCatalogService.API));

        assertThatThrownBy(() -> publisher.queryFromParent(
            "customer_template_query", "api_service_query", Map.of("limit", 10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parent mismatch");
    }

    @Test
    void resolvesPythonTemplatesFromTheSameFixedCatalogContract() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, catalog);
        McpInvocationContext.Context context = context("service-1", "role-1");
        Set<String> allowed = Set.of("python-template-1");
        when(bindings.requireRoute("analytics_template_query")).thenReturn(
            route("analytics_template_query", "python_analysis_query", TemplateAssetCatalogService.PYTHON));
        when(bindings.resolvePolicy(context, "analytics_template_query"))
            .thenReturn(new TemplateQueryBindingService.PolicyResolution(
                Map.of("python_runtime", allowed), Set.of("python_analysis_query"),
                "policy-v1", false, 1, Instant.parse("2026-08-07T00:00:00Z")));
        when(catalog.listEnabled()).thenReturn(List.of(
            asset(TemplateAssetCatalogService.PYTHON, "python-template-1", Map.of("type", "object")),
            asset(TemplateAssetCatalogService.PYTHON, "unbound-template", Map.of())));

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.queryFromParent("analytics_template_query", "python_analysis_query",
                Map.of("assetType", "python_runtime", "limit", 20));
        }

        assertThat(result.get("templates").toString()).contains("python-template-1")
            .doesNotContain("unbound-template");
    }

    private TemplateQueryMcpToolPublisher publisher(TemplateQueryBindingService bindings,
                                                     TemplateAssetCatalogService catalog) {
        return new TemplateQueryMcpToolPublisher(
            mock(McpSyncServer.class), bindings, bindings, catalog,
            mock(AgentRuntimeGovernanceFactory.class), mock(McpToolConcurrencyManager.class));
    }

    private TemplateAssetCatalogService.TemplateAsset asset(
        String assetType, String templateId, Map<String, Object> parameterSchema) {
        return new TemplateAssetCatalogService.TemplateAsset(
            assetType + ":" + templateId, assetType, templateId, templateId,
            "", "", "", "", parameterSchema);
    }

    private TemplateQueryBindingService.PolicyResolution policy(Map<String, Set<String>> allowed) {
        return new TemplateQueryBindingService.PolicyResolution(
            allowed, Set.of("api_template_query"), "policy-v1", false,
            allowed.values().stream().mapToInt(Set::size).sum(), Instant.parse("2026-08-07T00:00:00Z"));
    }

    private TemplateQueryRouteResolver.Route route(String child, String parent, String assetType) {
        return new TemplateQueryRouteResolver.Route(child, parent, assetType);
    }

    private McpInvocationContext.Context context(String serviceId, String roles) {
        return new McpInvocationContext.Context(
            "caller", "127.0.0.1", "test", "request-1", serviceId,
            "user-1", "user", "tenant-1", roles, null, "DEV", "trace-1",
            null, null, null, null
        );
    }
}
