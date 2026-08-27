package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.api.publication.ApiTemplateDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.ops.discovery.CommandTemplateDiscoveryService;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TemplateQueryMcpToolPublisherTest {

    @Test
    void removesLegacyGenericTemplateQueryAndDoesNotPublishItAgain() {
        McpSyncServer server = mock(McpSyncServer.class);
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        TemplateQueryMcpToolPublisher publisher = new TemplateQueryMcpToolPublisher(
            server, bindings, mock(CommandTemplateDiscoveryService.class),
            mock(ApiTemplateDiscoveryMcpToolPublisher.class),
            new AgentRuntimeGovernanceFactory(new ObjectMapper()));
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
        TemplateQueryMcpToolPublisher publisher = new TemplateQueryMcpToolPublisher(
            server, bindings, mock(CommandTemplateDiscoveryService.class),
            mock(ApiTemplateDiscoveryMcpToolPublisher.class),
            new AgentRuntimeGovernanceFactory(new ObjectMapper()));
        when(bindings.publishedToolNames()).thenReturn(Set.of("customer_template_query"));
        when(bindings.parentToolName("customer_template_query")).thenReturn("api_template_query");

        publisher.refresh();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
            ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(captor.capture());
        assertThat(captor.getValue().tool().name()).isEqualTo("customer_template_query");
        assertThat(captor.getValue().tool().meta().toString())
            .contains("governanceEditable=false", "only_selected_templates=true", "allow_user_override=false",
                "parentToolName=api_service_query", "routingMode=api_parent_mcp_policy_filter",
                "mcpDynamicCapabilityRoute", "mcp.dynamic-capability-route.v1",
                "implementationIdentityArgument=_templateQueryChildToolName");
        assertThat((Map<String, Object>) captor.getValue().tool().inputSchema().get("properties"))
            .doesNotContainKeys("templateIds", "serviceId", "roleId", "governance");
    }

    @Test
    void returnsNothingWhenServiceAndRoleHaveNoBinding() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        ApiTemplateDiscoveryMcpToolPublisher apiDiscovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, discovery, apiDiscovery);
        McpInvocationContext.Context context = context("service-1", "role-1");
        when(bindings.resolvePolicy(context, "customer_template_query")).thenReturn(policy(Map.of()));

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.query("customer_template_query", Map.of("assetType", "api_service", "limit", 20));
        }

        assertThat(result.get("templates")).isEqualTo(List.of());
        assertThat(result.toString()).contains("configuredTemplateCount=0");
        verifyNoInteractions(discovery, apiDiscovery);
    }

    @Test
    void injectsOnlyServerBoundTemplateIdsIntoApiDiscovery() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        ApiTemplateDiscoveryMcpToolPublisher apiDiscovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, discovery, apiDiscovery);
        McpInvocationContext.Context context = context("service-1", "role-1");
        Set<String> allowed = Set.of("customer_query", "excluded_query");
        when(bindings.resolvePolicy(context, "customer_template_query"))
            .thenReturn(policy(Map.of("api_service", allowed)));
        when(bindings.parentToolName("customer_template_query")).thenReturn("api_template_query");
        when(apiDiscovery.queryAuthorized(org.mockito.ArgumentMatchers.anyMap(), eq(allowed)))
            .thenReturn(Map.of("templates", List.of(
                Map.of("templateId", "customer_query", "name", "Customer query"),
                Map.of("templateId", "excluded_query", "name", "Explicitly excluded"),
                Map.of("templateId", "unbound_template", "name", "Must be removed"))));

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.queryFromParent("customer_template_query", "api_template_query", Map.of(
                "assetType", "api_service",
                "templateIds", List.of("unbound_template"),
                "excludeTemplateIds", List.of("excluded_query"),
                "limit", 20
            ));
        }

        assertThat(result.get("templates").toString()).contains("customer_query")
            .doesNotContain("unbound_template", "excluded_query");
        assertThat(result.get("filterAudit").toString())
            .contains("filteredUnauthorizedCount=1", "filteredExcludedCount=1");
        verify(apiDiscovery).queryAuthorized(argThat(arguments ->
            allowed.equals(Set.copyOf((List<String>) arguments.get("templateIds")))), eq(allowed));
        verifyNoInteractions(discovery);
    }

    @Test
    void resolvesPolicyFromInvocationArgumentsWhenTransportThreadContextIsLost() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        ApiTemplateDiscoveryMcpToolPublisher apiDiscovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        TemplateQueryMcpToolPublisher publisher = publisher(
            bindings, mock(CommandTemplateDiscoveryService.class), apiDiscovery);
        Map<String, Object> arguments = Map.of(
            "tenantId", "tenant-1",
            "userId", "user-1",
            "roles", "role-1",
            "limit", 10
        );
        Set<String> allowed = Set.of("customer_query");
        when(bindings.parentToolName("customer_template_query")).thenReturn("api_template_query");
        when(bindings.resolvePolicy(null, "customer_template_query", arguments))
            .thenReturn(policy(Map.of("api_service", allowed)));
        when(apiDiscovery.queryAuthorized(org.mockito.ArgumentMatchers.anyMap(), eq(allowed)))
            .thenReturn(Map.of("templates", List.of(
                Map.of("templateId", "customer_query", "name", "Customer query"))));

        Map<String, Object> result = publisher.queryFromParent(
            "customer_template_query", "api_template_query", arguments);

        assertThat(result.get("templates").toString()).contains("customer_query");
        verify(bindings).resolvePolicy(null, "customer_template_query", arguments);
    }

    private TemplateQueryMcpToolPublisher publisher(TemplateQueryBindingService bindings,
                                                     CommandTemplateDiscoveryService discovery,
                                                     ApiTemplateDiscoveryMcpToolPublisher apiDiscovery) {
        return new TemplateQueryMcpToolPublisher(
            mock(McpSyncServer.class), bindings, discovery, apiDiscovery,
            mock(AgentRuntimeGovernanceFactory.class));
    }

    private TemplateQueryBindingService.PolicyResolution policy(Map<String, Set<String>> allowed) {
        return new TemplateQueryBindingService.PolicyResolution(
            allowed, Set.of("api_template_query"), "policy-v1", false,
            allowed.values().stream().mapToInt(Set::size).sum(), Instant.parse("2026-08-07T00:00:00Z"));
    }

    private McpInvocationContext.Context context(String serviceId, String roles) {
        return new McpInvocationContext.Context(
            "caller", "127.0.0.1", "test", "request-1", serviceId,
            "user-1", "user", "tenant-1", roles, null, "DEV", "trace-1",
            null, null, null, null
        );
    }
}
