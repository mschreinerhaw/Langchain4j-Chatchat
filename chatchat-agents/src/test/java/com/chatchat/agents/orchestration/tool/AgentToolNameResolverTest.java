package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.orchestration.retrieval.RegistryMcpCapabilityHierarchy;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowToolResolver;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpCapabilityNode;
import com.chatchat.common.tool.ToolMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolNameResolverTest {
    private final AgentToolNameResolver resolver = new AgentToolNameResolver();

    @Test
    void normalizesMcpPrefixedWebSearchWhenAvailableToolsAreNotProvided() {
        assertThat(resolver.normalizeToolName(
            "mcp_chatchat_mcp_server_web_search", List.of()))
            .isEqualTo("web_search");
    }

    @Test
    void resolvesMcpPrefixedWebSearchToLocalRegisteredTool() {
        assertThat(resolver.normalizeToolName(
            "mcp_chatchat_mcp_server_web_search", List.of("web_search", "document_search")))
            .isEqualTo("web_search");
    }

    @Test
    void keepsPublishedChildDistinctFromItsParentWorkflowNode() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String parent = "mcp_chatchat_mcp_server_api_service_query";
        String child = "mcp_chatchat_mcp_server_customer_service_template_query";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, child));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_service_query", null));
        when(registry.getToolMetadata(child)).thenReturn(metadata(
            child, "customer_service_template_query", "api_service_query"));
        AgentToolNameResolver treeAware = new AgentToolNameResolver(
            new RegistryMcpCapabilityHierarchy(registry));
        AgentWorkflowToolResolver workflows = new AgentWorkflowToolResolver(treeAware);

        assertThat(treeAware.sameToolName(parent, child)).isFalse();
        assertThat(workflows.missingMandatoryTools(List.of(parent, child), Set.of(parent)))
            .containsExactly(child);
        assertThat(workflows.nextMandatoryTool(List.of(parent, child), Set.of(parent)))
            .isEqualTo(child);
    }

    @Test
    void resolvesReviewerParentRetryToUniqueScopedBusinessImplementation() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String parent = "mcp_chatchat_mcp_server_api_service_query";
        String child = "mcp_chatchat_mcp_server_customer_service_template_query";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, child));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_service_query", null));
        when(registry.getToolMetadata(child)).thenReturn(metadata(
            child, "customer_service_template_query", "api_service_query"));
        RegistryMcpCapabilityHierarchy hierarchy = new RegistryMcpCapabilityHierarchy(registry);
        AgentToolNameResolver treeAware = new AgentToolNameResolver(hierarchy);

        assertThat(hierarchy.directlyInvocable(parent)).isFalse();
        assertThat(treeAware.resolveMostSpecificAvailableTool(parent, List.of(parent, child)))
            .isEqualTo(child);
    }

    @Test
    void doesNotGuessBetweenMultipleScopedBusinessImplementations() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String parent = "mcp_chatchat_mcp_server_api_service_query";
        String customer = "mcp_chatchat_mcp_server_customer_service_template_query";
        String account = "mcp_chatchat_mcp_server_account_service_template_query";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, customer, account));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_service_query", null));
        when(registry.getToolMetadata(customer)).thenReturn(metadata(
            customer, "customer_service_template_query", "api_service_query"));
        when(registry.getToolMetadata(account)).thenReturn(metadata(
            account, "account_service_template_query", "api_service_query"));
        AgentToolNameResolver treeAware = new AgentToolNameResolver(
            new RegistryMcpCapabilityHierarchy(registry));

        assertThat(treeAware.resolveMostSpecificAvailableTool(
            parent, List.of(parent, customer, account))).isNull();
    }

    private ToolMetadata metadata(String localName, String remoteName, String parentRemoteName) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("serviceId", "chatchat-mcp-server");
        node.put("toolName", localName);
        node.put("relationType", parentRemoteName == null
            ? McpCapabilityNode.RELATION_ROOT : McpCapabilityNode.RELATION_DELEGATES_TO_PARENT);
        if (parentRemoteName != null) node.put("parentToolName", parentRemoteName);
        Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("serviceId", "chatchat-mcp-server");
        extra.put("remoteToolName", remoteName);
        extra.put(McpCapabilityHierarchy.METADATA_KEY, node);
        return ToolMetadata.builder().id(localName).metadata(extra).build();
    }
}
