package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.tool.RegistryMcpCapabilityHierarchy;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.orchestration.workflow.AgentWorkflowToolResolver;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpCapabilityNode;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
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
        String parent = "mcp_chatchat_mcp_server_api_template_query";
        String child = "mcp_chatchat_mcp_server_customer_service_template_query";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, child));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_template_query", null));
        when(registry.getToolMetadata(child)).thenReturn(metadata(
            child, "customer_service_template_query", "api_template_query"));
        AgentToolNameResolver treeAware = new AgentToolNameResolver(
            new RegistryMcpCapabilityHierarchy(registry), registry);
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
        String parent = "mcp_chatchat_mcp_server_api_template_query";
        String child = "mcp_chatchat_mcp_server_customer_service_template_query";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, child));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_template_query", null));
        when(registry.getToolMetadata(child)).thenReturn(metadata(
            child, "customer_service_template_query", "api_template_query"));
        RegistryMcpCapabilityHierarchy hierarchy = new RegistryMcpCapabilityHierarchy(registry);
        AgentToolNameResolver treeAware = new AgentToolNameResolver(hierarchy);

        assertThat(hierarchy.directlyInvocable(parent)).isTrue();
        assertThat(treeAware.resolveMostSpecificAvailableTool(parent, List.of(parent, child)))
            .isEqualTo(child);
    }

    @Test
    void doesNotGuessBetweenMultipleScopedBusinessImplementations() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String parent = "mcp_chatchat_mcp_server_api_template_query";
        String customer = "mcp_chatchat_mcp_server_customer_service_template_query";
        String account = "mcp_chatchat_mcp_server_account_service_template_query";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, customer, account));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_template_query", null));
        when(registry.getToolMetadata(customer)).thenReturn(metadata(
            customer, "customer_service_template_query", "api_template_query"));
        when(registry.getToolMetadata(account)).thenReturn(metadata(
            account, "account_service_template_query", "api_template_query"));
        AgentToolNameResolver treeAware = new AgentToolNameResolver(
            new RegistryMcpCapabilityHierarchy(registry));

        assertThat(treeAware.resolveMostSpecificAvailableTool(
            parent, List.of(parent, customer, account))).isNull();
    }

    @Test
    void plannerSeesBusinessImplementationsInsteadOfTheirAbstractParent() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String parent = "mcp_chatchat_mcp_server_api_template_query";
        String customer = "mcp_chatchat_mcp_server_customer_service_template_query";
        String account = "mcp_chatchat_mcp_server_account_service_template_query";
        String executor = "mcp_chatchat_mcp_server_api_template_execute";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, customer, account, executor));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_template_query", null));
        when(registry.getToolMetadata(customer)).thenReturn(metadata(
            customer, "customer_service_template_query", "api_template_query"));
        when(registry.getToolMetadata(account)).thenReturn(metadata(
            account, "account_service_template_query", "api_template_query"));
        when(registry.getToolMetadata(executor)).thenReturn(metadata(
            executor, "api_template_execute", null));
        AgentToolNameResolver treeAware = new AgentToolNameResolver(
            new RegistryMcpCapabilityHierarchy(registry));

        assertThat(treeAware.plannerVisibleTools(List.of(parent, customer, account, executor)))
            .containsExactly(customer, account, executor);
        assertThat(treeAware.plannerInternalDelegations(List.of(parent, customer, account, executor)))
            .containsEntry(parent, List.of(customer, account))
            .doesNotContainKeys(customer, account, executor);
    }

    @Test
    void plannerKeepsAbstractParentAsFallbackWhenNoAuthorizedImplementationIsVisible() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String parent = "mcp_chatchat_mcp_server_api_template_query";
        String hiddenChild = "mcp_chatchat_mcp_server_customer_service_template_query";
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, hiddenChild));
        when(registry.getToolMetadata(parent)).thenReturn(metadata(parent, "api_template_query", null));
        when(registry.getToolMetadata(hiddenChild)).thenReturn(metadata(
            hiddenChild, "customer_service_template_query", "api_template_query"));
        AgentToolNameResolver treeAware = new AgentToolNameResolver(
            new RegistryMcpCapabilityHierarchy(registry));

        assertThat(treeAware.plannerVisibleTools(List.of(parent))).containsExactly(parent);
    }

    @Test
    void readsDiscoveryRoleOnlyFromLiveMcpRegistrationMetadata() {
        ToolRegistry registry = mock(ToolRegistry.class);
        String service = "mcp_vendor_opaque_service";
        String templates = "mcp_vendor_opaque_templates";
        String misleading = "mcp_vendor_customer_service_template_query";
        when(registry.getToolMetadata(service)).thenReturn(metadataWithRole(
            service, "opaque_service", ToolWorkflowRole.ASSET_DISCOVERY));
        when(registry.getToolMetadata(templates)).thenReturn(metadataWithRole(
            templates, "opaque_templates", ToolWorkflowRole.TEMPLATE_DISCOVERY));
        when(registry.getToolMetadata(misleading)).thenReturn(metadata(
            misleading, "customer_service_template_query", null));
        AgentToolNameResolver declared = new AgentToolNameResolver(
            new RegistryMcpCapabilityHierarchy(registry), registry);

        assertThat(declared.isAssetDiscoveryToolName(service)).isTrue();
        assertThat(declared.isTemplateDiscoveryToolName(templates)).isTrue();
        assertThat(declared.isTemplateDiscoveryToolName(misleading)).isFalse();
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

    private ToolMetadata metadataWithRole(String localName, String remoteName,
                                          ToolWorkflowRole role) {
        ToolMetadata base = metadata(localName, remoteName, null);
        Map<String, Object> extra = new java.util.LinkedHashMap<>(base.getMetadata());
        extra.put(ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(role, "test.protocol.v1", "filters"));
        return ToolMetadata.builder().id(localName).metadata(extra).build();
    }
}
