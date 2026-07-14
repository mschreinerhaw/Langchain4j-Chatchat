package com.chatchat.chat.interaction.service;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
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
        McpToolRegistryBridge mcpToolRegistryBridge = mock(McpToolRegistryBridge.class);
        AgentToolPolicyResolver resolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        String assetTool = "mcp_chatchat_mcp_server_asset_query";
        String templateTool = "mcp_chatchat_mcp_server_template_query";
        when(toolRegistry.hasTool(assetTool)).thenReturn(true);
        when(toolRegistry.hasTool(templateTool)).thenReturn(true);
        when(mcpToolRegistryBridge.listRegisteredTools()).thenReturn(List.of(
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
        McpToolRegistryBridge mcpToolRegistryBridge = mock(McpToolRegistryBridge.class);
        AgentToolPolicyResolver resolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        String templateTool = "mcp_chatchat_mcp_server_template_query";
        when(toolRegistry.hasTool(templateTool)).thenReturn(true);
        when(mcpToolRegistryBridge.listRegisteredTools()).thenReturn(List.of(
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

    private McpToolRegistryBridge.RegisteredMcpTool registered(String localName, String remoteName) {
        return new McpToolRegistryBridge.RegisteredMcpTool(
            localName,
            "chatchat-mcp-server",
            "chatchat_mcp_server",
            remoteName,
            remoteName
        );
    }

    private SkillDefinition skillWithWorkflow(List<Map<String, Object>> workflow) {
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
            null,
            Map.of("mcpWorkflow", workflow),
            List.of(),
            "published",
            false
        );
    }
}
