package com.chatchat.chat.interaction.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.model.SkillRoutingSettings;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.tool.ToolMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolPolicyResolverTest {
    @Test void selectedButUnavailableCapabilitiesDoNotBecomeAnUnboundConversation() {
        var registry = mock(ToolRegistry.class);
        var skills = mock(SkillCatalogService.class);
        var catalog = mock(McpToolCatalogQueryPort.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("not-published"));
        var snapshot = new AgentToolPolicyResolver(registry, skills, catalog)
            .planningSnapshot(InteractionRequest.builder().build(), agent);
        assertThat(snapshot.owner()).isEqualTo(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS);
        assertThat(snapshot.toolPurposes()).isEmpty();
        org.mockito.Mockito.verify(catalog, org.mockito.Mockito.times(1)).registeredTools();
    }

    @Test void entrySnapshotCannotDriftWhenSourceMapsAreMutated() {
        Map<String, Object> source = new java.util.LinkedHashMap<>(Map.of("data_type", "ASSET_QUERY"));
        var snapshot = new WorkflowEntryPlan(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS, "test", List.of(source));
        source.put("data_type", "DATA_FETCH");
        assertThat(snapshot.toolPurposes()).containsExactly(Map.of("data_type", "ASSET_QUERY"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.toolPurposes().get(0).put("data_type", "DATA_FETCH"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void templateDiscoveryOwnsPlanningWithoutAnyDataFetchPublication() {
        var registry = mock(ToolRegistry.class);
        var skills = mock(SkillCatalogService.class);
        var catalog = mock(McpToolCatalogQueryPort.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        when(catalog.registeredTools()).thenReturn(List.of());
        when(registry.getAllToolNames()).thenReturn(java.util.Set.of("opaque"));
        when(skills.resolveTools(org.mockito.ArgumentMatchers.eq(agent), org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of("opaque"));
        when(registry.getToolMetadata("opaque")).thenReturn(ToolMetadata.builder().id("opaque")
            .metadata(Map.of("workflowContract", com.chatchat.common.tool.ToolWorkflowContract.declaration(
                com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_DISCOVERY, "test-template", "query"))).build());
        var resolver = new AgentToolPolicyResolver(registry, skills, catalog);
        assertThat(resolver.planningSnapshot(InteractionRequest.builder().build(), agent).owner()).isEqualTo(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME);
        assertThat(resolver.planningSnapshot(InteractionRequest.builder().availableTools(List.of("unselected")).build(), agent).owner()).isEqualTo(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS);
        when(registry.getToolMetadata("opaque")).thenReturn(ToolMetadata.builder().id("opaque").dataType("ASSET_QUERY").build());
        assertThat(resolver.planningSnapshot(InteractionRequest.builder().build(), agent).owner()).isEqualTo(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS);
        when(agent.workflowConfig()).thenReturn(Map.of("mcpWorkflow", List.of(Map.of("tool", "opaque"))));
        assertThat(resolver.planningSnapshot(InteractionRequest.builder().build(), agent).owner()).isEqualTo(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME);
    }
    @Test void selectionIsBasedOnConfigurationNotRegistryAvailability() {
        var request = InteractionRequest.builder().build();
        var agent = mock(SkillDefinition.class);
        assertThat(AgentToolPolicyResolver.hasSelectedCapabilities(request, agent)).isFalse();
        when(agent.boundMcpToolNames()).thenReturn(List.of("selected_tool"));
        assertThat(AgentToolPolicyResolver.hasSelectedCapabilities(request, agent)).isTrue();
        when(agent.boundMcpToolNames()).thenReturn(List.of());
        when(agent.toolConfigs()).thenReturn(List.of(new com.chatchat.chat.skills.model.SkillToolConfig(
            "disabled_tool", "", "", "", List.of(), "", 1, false)));
        assertThat(AgentToolPolicyResolver.hasSelectedCapabilities(request, agent)).isFalse();
        request.setAvailableTools(List.of("requested_tool"));
        assertThat(AgentToolPolicyResolver.hasSelectedCapabilities(request, agent)).isTrue();
        request.setAvailableTools(List.of());
        when(agent.boundDocumentIds()).thenReturn(List.of("document-1"));
        assertThat(AgentToolPolicyResolver.hasSelectedCapabilities(request, agent)).isTrue();
    }
    @Test
    void planningPurposesUseOnlySelectedActiveBindingsAndNeverGuessFromNames() {
        var registry = mock(ToolRegistry.class);
        var skills = mock(SkillCatalogService.class);
        var catalog = mock(McpToolCatalogQueryPort.class);
        var retriever = mock(McpToolCandidateRetriever.class);
        var agent = mock(SkillDefinition.class);
        when(agent.id()).thenReturn("agent");
        var names = List.of("opaque", "document_search", "disabled", "unbound");
        when(registry.getAllToolNames()).thenReturn(java.util.Set.copyOf(names));
        when(catalog.registeredTools()).thenReturn(List.of());
        when(skills.resolveTools(org.mockito.ArgumentMatchers.eq(agent), org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of("opaque", "document_search", "disabled"));
        when(registry.getToolMetadata("opaque")).thenReturn(ToolMetadata.builder().id("opaque")
            .metadata(Map.of("mcpToolMeta", Map.of("data_type", "TEMPLATE_QUERY"))).build());
        when(registry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().id("document_search").build());
        when(registry.getToolMetadata("disabled")).thenReturn(ToolMetadata.builder().id("disabled").publicationStatus("disabled").build());
        var resolver = new AgentToolPolicyResolver(registry, skills, catalog, retriever);
        var purposes = resolver.planningSnapshot(InteractionRequest.builder().availableTools(names).build(), agent).toolPurposes();
        assertThat(purposes).containsExactly(
            Map.of("tool", "opaque", "data_type", "TEMPLATE_QUERY", "description", ""),
            Map.of("tool", "document_search", "data_type", "UNKNOWN", "description", ""));
        assertThat(resolver.planningSnapshot(InteractionRequest.builder().availableTools(List.of("opaque")).build(), agent).toolPurposes())
            .hasSize(1);
        org.mockito.Mockito.verifyNoInteractions(retriever);
    }

    @Test
    void discoveryRespectsGeneralTopKAndDoesNotRestoreUnselectedTools() {
        ToolRegistry registry = mock(ToolRegistry.class);
        SkillCatalogService skills = mock(SkillCatalogService.class);
        McpToolCatalogQueryPort catalog = mock(McpToolCatalogQueryPort.class);
        McpToolCandidateRetriever retriever = mock(McpToolCandidateRetriever.class);
        SkillDefinition agent = mock(SkillDefinition.class);
        when(agent.id()).thenReturn("asset");
        var names = List.of("discover", "denied", "execute", "unbound");
        when(catalog.registeredTools()).thenReturn(names.stream().map(name -> registered(name, name)).toList());
        when(registry.getAllToolNames()).thenReturn(java.util.Set.copyOf(names));
        for (String name : names) {
            var role = name.equals("execute") ? com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_EXECUTION
                : com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_DISCOVERY;
            when(registry.getToolMetadata(name)).thenReturn(ToolMetadata.builder().agentCompatible(true)
                .metadata(Map.of("workflowContract", com.chatchat.common.tool.ToolWorkflowContract.declaration(role, "templates", "query"))).build());
        }
        when(skills.resolveTools(org.mockito.ArgumentMatchers.eq("asset"), org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of("discover", "denied", "execute"));
        var request = InteractionRequest.builder().skillId("asset").tenantId("t").userId("u").query("净值比对的用途").build();
        when(retriever.retrieve(request, List.of("discover", "denied", "execute"), 3))
            .thenReturn(new McpToolCandidateRetriever.Selection(java.util.Set.of("discover", "denied"),
                java.util.Set.of("discover", "execute"), List.of("execute")));
        var result = new AgentToolPolicyResolver(registry, skills, catalog, retriever).resolveTemplateDiscovery(request, agent);
        assertThat(result.availableTools()).isEmpty();
        assertThat(result.boundToolCount()).isEqualTo(1);
        assertThat(result.eligibleToolCount()).isEqualTo(0);
        org.mockito.Mockito.verify(retriever).retrieve(request, List.of("discover", "denied", "execute"), 3);
    }

    @Test
    void presentsOnlyAuthorizedRankedMcpToolsWhenDatabaseScopeIsAvailable() {
        ToolRegistry registry = mock(ToolRegistry.class);
        SkillCatalogService skills = mock(SkillCatalogService.class);
        McpToolCatalogQueryPort catalog = mock(McpToolCatalogQueryPort.class);
        McpToolCandidateRetriever retriever = mock(McpToolCandidateRetriever.class);
        List<String> names = List.of("mcp_assets", "mcp_trades", "mcp_forbidden");
        when(catalog.registeredTools()).thenReturn(List.of(
            registered("mcp_assets", "assets"), registered("mcp_trades", "trades"),
            registered("mcp_forbidden", "forbidden")));
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(3))).thenReturn(new McpToolCandidateRetriever.Selection(
                java.util.Set.copyOf(names), java.util.Set.of("mcp_assets", "mcp_trades"),
                List.of("mcp_assets")));
        AgentToolPolicyResolver resolver = new AgentToolPolicyResolver(registry, skills, catalog, retriever);

        AgentToolPolicyResolver.ToolPolicy policy = resolver.resolve(InteractionRequest.builder()
            .tenantId("tenant-1").userId("user-1").query("customer assets")
            .availableTools(names).build(), null);

        assertThat(policy.availableTools()).containsExactly("mcp_assets");
        assertThat(policy.skippedToolReasons()).containsKeys("mcp_trades", "mcp_forbidden");
    }

    @Test
    void excludesRequiredMcpToolMissingFromDatabase() {
        ToolRegistry registry = mock(ToolRegistry.class);
        SkillCatalogService skills = mock(SkillCatalogService.class);
        McpToolCatalogQueryPort catalog = mock(McpToolCatalogQueryPort.class);
        McpToolCandidateRetriever retriever = mock(McpToolCandidateRetriever.class);
        String missing = "mcp_missing";
        when(catalog.registeredTools()).thenReturn(List.of(registered(missing, "missing")));
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.anyInt())).thenReturn(new McpToolCandidateRetriever.Selection(
                java.util.Set.of(), java.util.Set.of(), List.of()));
        AgentToolPolicyResolver resolver = new AgentToolPolicyResolver(registry, skills, catalog, retriever);
        SkillDefinition skill = skillWithWorkflow(List.of(Map.of("step", "lookup", "tool", missing,
            "required", true)));

        AgentToolPolicyResolver.ToolPolicy policy = resolver.resolve(InteractionRequest.builder()
            .tenantId("tenant-1").userId("user-1").query("lookup")
            .availableTools(List.of(missing)).build(), skill);

        assertThat(policy.availableTools()).doesNotContain(missing);
        assertThat(policy.requiredTools()).doesNotContain(missing);
        assertThat(policy.requiredCapabilityGaps()).containsKey(missing);
    }

    @Test
    void doesNotAutoAddRegisteredWorkflowToolThatUserDidNotBind() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolCatalogQueryPort mcpToolRegistryBridge = mock(McpToolCatalogQueryPort.class);
        AgentToolPolicyResolver resolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        String assetTool = "mcp_chatchat_mcp_server_asset_query";
        String templateTool = "mcp_chatchat_mcp_server_template_query";
        when(toolRegistry.hasTool(assetTool)).thenReturn(true);
        when(toolRegistry.hasTool(templateTool)).thenReturn(true);
        when(mcpToolRegistryBridge.registeredTools()).thenReturn(List.of(
            registered(assetTool, "asset_query"),
            registered(templateTool, "template_query")
        ));

        AgentToolPolicyResolver.ToolPolicy policy = resolver.resolve(
            InteractionRequest.builder()
                .query("分析本地MySQL测试服务InnoDB状态")
                .availableTools(List.of(templateTool))
                .build(),
            skillWithWorkflow(List.of(
                Map.of("step", "asset_discovery", "tool", "asset_query", "required", true),
                Map.of("step", "template_retrieval", "tool", "template_query", "required", true,
                    "dependsOn", List.of("asset_discovery"))
            ))
        );

        assertThat(policy.availableTools()).containsExactly(templateTool);
        assertThat(policy.requiredTools()).containsExactly(templateTool);
        assertThat(policy.workflowAutoAddedTools()).isEmpty();
        assertThat(policy.requiredCapabilityGaps()).containsKey("asset_query");
        assertThat(policy.skippedToolReasons())
            .containsEntry("asset_query", "required workflow tool is not bound/available for this Agent");
    }

    @Test
    void doesNotAutoAddUnregisteredRequiredWorkflowToolAndReportsReason() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolCatalogQueryPort mcpToolRegistryBridge = mock(McpToolCatalogQueryPort.class);
        AgentToolPolicyResolver resolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        String templateTool = "mcp_chatchat_mcp_server_template_query";
        when(toolRegistry.hasTool(templateTool)).thenReturn(true);
        when(mcpToolRegistryBridge.registeredTools()).thenReturn(List.of(
            registered(templateTool, "template_query")
        ));

        AgentToolPolicyResolver.ToolPolicy policy = resolver.resolve(
            InteractionRequest.builder()
                .query("分析本地MySQL测试服务InnoDB状态")
                .availableTools(List.of(templateTool))
                .build(),
            skillWithWorkflow(List.of(
                Map.of("step", "asset_discovery", "tool", "asset_query", "required", true),
                Map.of("step", "template_retrieval", "tool", "template_query", "required", true)
            ))
        );

        assertThat(policy.availableTools()).containsExactly(templateTool);
        assertThat(policy.requiredTools()).containsExactly(templateTool);
        assertThat(policy.workflowAutoAddedTools()).isEmpty();
        assertThat(policy.skippedToolReasons())
            .containsEntry("asset_query", "required workflow tool is not registered in MCP registry");
    }

    @Test
    void keepsCompleteOptionalToolSetVisibleForModelPlanning() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolCatalogQueryPort catalog = mock(McpToolCatalogQueryPort.class);
        AgentToolPolicyResolver resolver = new AgentToolPolicyResolver(
            toolRegistry, skillCatalogService, catalog);
        List<String> tools = List.of("mcp_assets", "mcp_trades", "mcp_risk", "mcp_news");
        when(catalog.registeredTools()).thenReturn(List.of(
            registered("mcp_assets", "assets"), registered("mcp_trades", "trades"),
            registered("mcp_risk", "risk"), registered("mcp_news", "news")));
        for (String tool : tools) {
            when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool).title("客户交易资产风险分析").description("客户跨领域分析数据").build());
        }

        SkillDefinition skill = skillWithWorkflow(List.of(),
            new SkillRoutingSettings(true, true, 2, 2));
        AgentToolPolicyResolver.ToolPolicy policy = resolver.resolve(
            InteractionRequest.builder().query("分析客户交易资产和风险").availableTools(tools).build(), skill);

        assertThat(policy.availableTools()).containsExactlyInAnyOrderElementsOf(tools);
        assertThat(policy.optionalTools()).containsExactlyInAnyOrderElementsOf(tools);
        assertThat(policy.selectedCandidateTools()).hasSize(2);
        assertThat(policy.skippedToolReasons()).isEmpty();
    }

    private McpToolCatalogQueryPort.RegisteredTool registered(String localName, String remoteName) {
        return new McpToolCatalogQueryPort.RegisteredTool(
            localName,
            "chatchat-mcp-server",
            "chatchat_mcp_server",
            remoteName,
            remoteName
        );
    }

    private SkillDefinition skillWithWorkflow(List<Map<String, Object>> workflow) {
        return skillWithWorkflow(workflow, null);
    }

    private SkillDefinition skillWithWorkflow(List<Map<String, Object>> workflow,
                                               SkillRoutingSettings routingSettings) {
        return new SkillDefinition(
            "ops",
            "Ops",
            "",
            List.of(),
            List.of(),
            "agent_chat",
            null,
            "system",
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            routingSettings,
            Map.of("mcpWorkflow", workflow),
            List.of(),
            "published",
            false
        );
    }
}
