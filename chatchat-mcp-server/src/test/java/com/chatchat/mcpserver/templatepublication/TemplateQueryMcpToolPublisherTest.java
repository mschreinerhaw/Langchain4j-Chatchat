package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.api.ApiTemplateDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.ops.CommandTemplateDiscoveryService;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TemplateQueryMcpToolPublisherTest {

    @Test
    void publishesFixedReviewedGovernanceWithoutEditableScopeArguments() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        TemplateQueryMcpToolPublisher publisher = new TemplateQueryMcpToolPublisher(
            server, mock(TemplateQueryBindingService.class), mock(CommandTemplateDiscoveryService.class),
            mock(ApiTemplateDiscoveryMcpToolPublisher.class),
            new AgentRuntimeGovernanceFactory(new ObjectMapper()));

        publisher.refresh();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> captor =
            ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(captor.capture());
        assertThat(captor.getValue().tool().name()).isEqualTo("template_query");
        assertThat(captor.getValue().tool().meta().toString())
            .contains("governanceEditable=false", "only_selected_templates=true", "allow_user_override=false");
        assertThat(captor.getValue().tool().inputSchema().properties())
            .doesNotContainKeys("templateIds", "serviceId", "roleId", "governance");
    }

    @Test
    void returnsNothingWhenServiceAndRoleHaveNoBinding() {
        TemplateQueryBindingService bindings = mock(TemplateQueryBindingService.class);
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        ApiTemplateDiscoveryMcpToolPublisher apiDiscovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        TemplateQueryMcpToolPublisher publisher = publisher(bindings, discovery, apiDiscovery);
        McpInvocationContext.Context context = context("service-1", "role-1");
        when(bindings.allowedTemplates(context)).thenReturn(Map.of());

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.query(Map.of("assetType", "api_service", "limit", 20));
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
        Set<String> allowed = Set.of("customer_query");
        when(bindings.allowedTemplates(context)).thenReturn(Map.of("api_service", allowed));
        when(apiDiscovery.queryAuthorized(org.mockito.ArgumentMatchers.anyMap(), eq(allowed)))
            .thenReturn(Map.of("templates", List.of(Map.of(
                "templateId", "customer_query", "name", "Customer query"))));

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            result = publisher.query(Map.of(
                "assetType", "api_service",
                "templateIds", List.of("unbound_template"),
                "limit", 20
            ));
        }

        assertThat(result.get("templates").toString()).contains("customer_query").doesNotContain("unbound_template");
        verify(apiDiscovery).queryAuthorized(argThat(arguments ->
            allowed.equals(Set.copyOf((List<String>) arguments.get("templateIds")))), eq(allowed));
        verifyNoInteractions(discovery);
    }

    private TemplateQueryMcpToolPublisher publisher(TemplateQueryBindingService bindings,
                                                     CommandTemplateDiscoveryService discovery,
                                                     ApiTemplateDiscoveryMcpToolPublisher apiDiscovery) {
        return new TemplateQueryMcpToolPublisher(
            mock(McpSyncServer.class), bindings, discovery, apiDiscovery,
            mock(AgentRuntimeGovernanceFactory.class));
    }

    private McpInvocationContext.Context context(String serviceId, String roles) {
        return new McpInvocationContext.Context(
            "caller", "127.0.0.1", "test", "request-1", serviceId,
            "user-1", "user", "tenant-1", roles, null, "DEV", "trace-1",
            null, null, null, null
        );
    }
}
