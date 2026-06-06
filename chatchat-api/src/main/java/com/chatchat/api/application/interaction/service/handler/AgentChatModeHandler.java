package com.chatchat.api.application.interaction.service.handler;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.agent.AgentOrchestrator;
import com.chatchat.api.application.interaction.model.InteractionContext;
import com.chatchat.api.application.interaction.model.InteractionMode;
import com.chatchat.api.application.interaction.model.InteractionRequest;
import com.chatchat.api.application.interaction.model.InteractionResponse;
import com.chatchat.api.application.interaction.service.InteractionModeHandler;
import com.chatchat.api.mcp.service.McpToolRegistryBridge;
import com.chatchat.api.skills.SkillCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent interaction handler with tool orchestration.
 */
@Component
@RequiredArgsConstructor
public class AgentChatModeHandler implements InteractionModeHandler {

    private final AgentOrchestrator agentOrchestrator;
    private final ToolRegistry toolRegistry;
    private final SkillCatalogService skillCatalogService;
    private final McpToolRegistryBridge mcpToolRegistryBridge;

    @Override
    public InteractionMode mode() {
        return InteractionMode.AGENT_CHAT;
    }

    @Override
    public InteractionResponse handle(InteractionRequest request, InteractionContext context) {
        List<String> tools = request.getAvailableTools();
        if (tools == null || tools.isEmpty()) {
            tools = discoverDefaultTools(request.getSkillId());
        }
        String systemPrompt = resolveSystemPrompt(request);

        AgentOrchestrator.AgentExecutionResult result = agentOrchestrator.executeAgent(
            request.getQuery(),
            tools,
            systemPrompt,
            request.getSkillId(),
            context.requestId(),
            context.conversationId(),
            request.getUserId()
        );

        return InteractionResponse.builder()
            .answer(result.answer())
            .toolTraces(result.toolTraces())
            .metadata(java.util.Map.of(
                "availableTools", tools,
                "skillId", request.getSkillId() == null ? "general" : request.getSkillId(),
                "agent", result.metadata(),
                "handler", "AgentChatModeHandler"
            ))
            .build();
    }

    private List<String> discoverDefaultTools(String skillId) {
        Map<String, List<String>> mcpToolsByServiceId = new LinkedHashMap<>();
        mcpToolRegistryBridge.listRegisteredTools().forEach(tool ->
            mcpToolsByServiceId.computeIfAbsent(tool.serviceId(), ignored -> new java.util.ArrayList<>())
                .add(tool.localToolName())
        );
        return skillCatalogService.resolveTools(skillId, toolRegistry.getAllToolNames(), mcpToolsByServiceId);
    }

    private String resolveSystemPrompt(InteractionRequest request) {
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            return request.getSystemPrompt();
        }
        return skillCatalogService.resolve(request.getSkillId()).systemPrompt();
    }
}
