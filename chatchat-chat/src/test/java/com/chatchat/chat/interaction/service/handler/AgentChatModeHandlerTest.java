package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.service.AgentToolPolicyResolver;
import com.chatchat.chat.interaction.service.ConversationMemoryService;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.skills.SkillRoutingSettings;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatModeHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void agentPromptIncludesConversationHistory() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolRegistryBridge mcpToolRegistryBridge = mock(McpToolRegistryBridge.class);
        AgentToolPolicyResolver toolPolicyResolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        AgentChatModeHandler handler = new AgentChatModeHandler(
            orchestrator,
            skillCatalogService,
            toolPolicyResolver
        );

        when(skillCatalogService.resolve("ops")).thenReturn(skillWithoutWebSearch());
        when(mcpToolRegistryBridge.listRegisteredTools()).thenReturn(List.of());
        when(orchestrator.executeAgent(
            anyString(),
            isNull(),
            anyList(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyList(),
            anyBoolean()
        )).thenReturn(agentResult("ok"));

        InteractionRequest request = InteractionRequest.builder()
            .mode("agent_chat")
            .skillId("ops")
            .query("继续刚才的问题")
            .userId("u1")
            .build();
        InteractionContext context = InteractionContext.builder()
            .requestId("req")
            .conversationId("conv")
            .mode(InteractionMode.AGENT_CHAT)
            .history(List.of(
                new ConversationMemoryService.MessageSnapshot("user", "Kafka Connect 安全认证与启动", 1L),
                new ConversationMemoryService.MessageSnapshot("assistant", "我已经找到启动和认证相关线索。", 2L)
            ))
            .build();

        var response = handler.handle(request, context);

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(orchestrator).executeAgent(
            anyString(),
            isNull(),
            anyList(),
            systemPrompt.capture(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyList(),
            anyBoolean()
        );

        assertThat(systemPrompt.getValue())
            .contains("system")
            .contains("Previous conversation transcript")
            .contains("user: Kafka Connect 安全认证与启动")
            .contains("assistant: 我已经找到启动和认证相关线索。");
        assertThat(response.getMetadata()).containsEntry("historyUsed", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchToggleRequiresRegisteredMcpWebSearchTool() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolRegistryBridge mcpToolRegistryBridge = mock(McpToolRegistryBridge.class);
        AgentToolPolicyResolver toolPolicyResolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        AgentChatModeHandler handler = new AgentChatModeHandler(
            orchestrator,
            skillCatalogService,
            toolPolicyResolver
        );

        when(skillCatalogService.resolve("ops")).thenReturn(skillWithWebSearch());
        when(mcpToolRegistryBridge.listRegisteredTools()).thenReturn(List.of(
            new McpToolRegistryBridge.RegisteredMcpTool(
                "mcp_chatchat_mcp_server_web_search",
                "svc-1",
                "ChatChat MCP Server",
                "web_search",
                "Search the web"
            )
        ));
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_web_search")).thenReturn(true);
        when(orchestrator.executeAgent(
            anyString(),
            isNull(),
            anyList(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyList(),
            anyBoolean()
        )).thenReturn(agentResult("ok"));

        InteractionRequest request = InteractionRequest.builder()
            .mode("agent_chat")
            .skillId("ops")
            .query("news today")
            .userId("u1")
            .availableTools(List.of("web_search", "document_search"))
            .toolInput(Map.of("webSearch", true))
            .build();
        InteractionContext context = InteractionContext.builder()
            .requestId("req")
            .conversationId("conv")
            .mode(InteractionMode.AGENT_CHAT)
            .history(List.of())
            .build();

        handler.handle(request, context);

        ArgumentCaptor<List<String>> availableTools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> requiredTools = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).executeAgent(
            anyString(),
            isNull(),
            availableTools.capture(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            requiredTools.capture(),
            anyBoolean()
        );

        assertThat(availableTools.getValue())
            .containsExactly("mcp_chatchat_mcp_server_web_search", "document_search");
        assertThat(requiredTools.getValue())
            .containsExactly("mcp_chatchat_mcp_server_web_search");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchToggleDoesNothingWhenAgentDidNotEnableWebSearch() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolRegistryBridge mcpToolRegistryBridge = mock(McpToolRegistryBridge.class);
        AgentToolPolicyResolver toolPolicyResolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        AgentChatModeHandler handler = new AgentChatModeHandler(
            orchestrator,
            skillCatalogService,
            toolPolicyResolver
        );

        when(skillCatalogService.resolve("ops")).thenReturn(skillWithoutWebSearch());
        when(mcpToolRegistryBridge.listRegisteredTools()).thenReturn(List.of(
            new McpToolRegistryBridge.RegisteredMcpTool(
                "mcp_chatchat_mcp_server_web_search",
                "svc-1",
                "ChatChat MCP Server",
                "web_search",
                "Search the web"
            )
        ));
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_web_search")).thenReturn(true);
        when(orchestrator.executeAgent(
            anyString(),
            isNull(),
            anyList(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyList(),
            anyBoolean()
        )).thenReturn(agentResult("ok"));

        InteractionRequest request = InteractionRequest.builder()
            .mode("agent_chat")
            .skillId("ops")
            .query("news today")
            .userId("u1")
            .availableTools(List.of("web_search", "document_search"))
            .toolInput(Map.of("webSearch", true))
            .build();
        InteractionContext context = InteractionContext.builder()
            .requestId("req")
            .conversationId("conv")
            .mode(InteractionMode.AGENT_CHAT)
            .history(List.of())
            .build();

        handler.handle(request, context);

        ArgumentCaptor<List<String>> availableTools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> requiredTools = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).executeAgent(
            anyString(),
            isNull(),
            availableTools.capture(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            requiredTools.capture(),
            anyBoolean()
        );

        assertThat(availableTools.getValue()).containsExactly("document_search");
        assertThat(requiredTools.getValue()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentWorkflowRequiresDocumentSearchBeforeWebSearch() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolRegistryBridge mcpToolRegistryBridge = mock(McpToolRegistryBridge.class);
        AgentToolPolicyResolver toolPolicyResolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        AgentChatModeHandler handler = new AgentChatModeHandler(
            orchestrator,
            skillCatalogService,
            toolPolicyResolver
        );

        when(skillCatalogService.resolve("ops")).thenReturn(skillWithDocumentsAndWebSearch());
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(mcpToolRegistryBridge.listRegisteredTools()).thenReturn(List.of(
            new McpToolRegistryBridge.RegisteredMcpTool(
                "mcp_chatchat_mcp_server_web_search",
                "svc-1",
                "ChatChat MCP Server",
                "web_search",
                "Search the web"
            )
        ));
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_web_search")).thenReturn(true);
        when(orchestrator.executeAgent(
            anyString(),
            isNull(),
            anyList(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyList(),
            anyBoolean()
        )).thenReturn(agentResult("ok"));

        InteractionRequest request = InteractionRequest.builder()
            .mode("agent_chat")
            .skillId("ops")
            .query("内部定义是什么，顺便联网验证")
            .userId("u1")
            .availableTools(List.of("web_search"))
            .toolInput(Map.of("documentWorkflow", true, "webSearch", true))
            .build();
        InteractionContext context = InteractionContext.builder()
            .requestId("req")
            .conversationId("conv")
            .mode(InteractionMode.AGENT_CHAT)
            .history(List.of())
            .build();

        handler.handle(request, context);

        ArgumentCaptor<List<String>> availableTools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> requiredTools = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).executeAgent(
            anyString(),
            isNull(),
            availableTools.capture(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            requiredTools.capture(),
            anyBoolean()
        );

        assertThat(availableTools.getValue())
            .containsExactly("document_search", "mcp_chatchat_mcp_server_web_search");
        assertThat(requiredTools.getValue())
            .containsExactly("document_search", "mcp_chatchat_mcp_server_web_search");
    }

    @Test
    @SuppressWarnings("unchecked")
    void maxRelevantMcpToolsComesFromAgentRoutingSettings() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpToolRegistryBridge mcpToolRegistryBridge = mock(McpToolRegistryBridge.class);
        AgentToolPolicyResolver toolPolicyResolver = new AgentToolPolicyResolver(
            toolRegistry,
            skillCatalogService,
            mcpToolRegistryBridge
        );
        AgentChatModeHandler handler = new AgentChatModeHandler(
            orchestrator,
            skillCatalogService,
            toolPolicyResolver
        );

        when(skillCatalogService.resolve("ops")).thenReturn(skillWithMaxRelevantMcpTools(5));
        when(mcpToolRegistryBridge.listRegisteredTools()).thenReturn(List.of());
        when(orchestrator.executeAgent(
            anyString(),
            isNull(),
            anyList(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyList(),
            anyBoolean()
        )).thenReturn(agentResult("ok"));

        InteractionRequest request = InteractionRequest.builder()
            .mode("agent_chat")
            .skillId("ops")
            .query("alpha analysis")
            .userId("u1")
            .availableTools(List.of(
                "mcp_alpha_1",
                "mcp_alpha_2",
                "mcp_alpha_3",
                "mcp_alpha_4",
                "mcp_alpha_5",
                "mcp_alpha_6"
            ))
            .build();
        InteractionContext context = InteractionContext.builder()
            .requestId("req")
            .conversationId("conv")
            .mode(InteractionMode.AGENT_CHAT)
            .history(List.of())
            .build();

        handler.handle(request, context);

        ArgumentCaptor<List<String>> availableTools = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).executeAgent(
            anyString(),
            isNull(),
            availableTools.capture(),
            anyString(),
            isNull(),
            anyList(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyList(),
            anyBoolean()
        );

        assertThat(availableTools.getValue())
            .containsExactly("mcp_alpha_1", "mcp_alpha_2", "mcp_alpha_3", "mcp_alpha_4", "mcp_alpha_5");
    }

    private SkillDefinition skillWithWebSearch() {
        return skill(List.of("mcp_chatchat_mcp_server_web_search"));
    }

    private SkillDefinition skillWithoutWebSearch() {
        return skill(List.of());
    }

    private SkillDefinition skillWithDocumentsAndWebSearch() {
        return skill(List.of("mcp_chatchat_mcp_server_web_search"), List.of("doc-1"));
    }

    private SkillDefinition skillWithMaxRelevantMcpTools(int maxRelevantMcpTools) {
        return skill(List.of(), List.of(), new SkillRoutingSettings(true, true, 3, maxRelevantMcpTools));
    }

    private SkillDefinition skill(List<String> boundMcpToolNames) {
        return skill(boundMcpToolNames, List.of());
    }

    private SkillDefinition skill(List<String> boundMcpToolNames, List<String> boundDocumentIds) {
        return skill(boundMcpToolNames, boundDocumentIds, null);
    }

    private SkillDefinition skill(List<String> boundMcpToolNames,
                                  List<String> boundDocumentIds,
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
            boundMcpToolNames,
            boundDocumentIds,
            List.of(),
            List.of(),
            routingSettings,
            Map.of(),
            List.of(),
            "published"
        );
    }

    private AgentOrchestrator.AgentExecutionResult agentResult(String answer) {
        return new AgentOrchestrator.AgentExecutionResult(
            answer,
            List.<InteractionToolTrace>of(),
            Map.<String, Object>of()
        );
    }
}
