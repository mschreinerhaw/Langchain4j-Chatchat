package com.chatchat.chat.interaction.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.skills.SkillRoutingSettings;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.tool.ToolMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolPolicyResolverTest {

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
