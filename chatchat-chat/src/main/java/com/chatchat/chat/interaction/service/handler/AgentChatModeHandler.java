package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionModeHandler;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
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
        SkillDefinition skill = skillCatalogService.resolve(request.getSkillId());
        List<String> tools = request.getAvailableTools();
        if (tools == null || tools.isEmpty()) {
            tools = discoverDefaultTools(request.getSkillId());
        }
        String systemPrompt = resolveSystemPrompt(request, skill);
        String modelName = skill.modelName() != null && !skill.modelName().isBlank()
            ? skill.modelName()
            : request.getModelName();

        AgentOrchestrator.AgentExecutionResult result = agentOrchestrator.executeAgent(
            request.getQuery(),
            tools,
            systemPrompt,
            modelName,
            skill.boundDocumentIds(),
            skill.boundDocumentTags(),
            request.getSkillId(),
            context.requestId(),
            context.conversationId(),
            request.getUserId(),
            hasMcpBinding(skill)
        );

        return InteractionResponse.builder()
            .answer(result.answer())
            .toolTraces(result.toolTraces())
            .metadata(java.util.Map.of(
                "availableTools", tools,
                "skillId", request.getSkillId() == null ? "general" : request.getSkillId(),
                "modelName", modelName,
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

    private String resolveSystemPrompt(InteractionRequest request, SkillDefinition skill) {
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            return request.getSystemPrompt();
        }
        return skill.systemPrompt();
    }

    private boolean hasMcpBinding(SkillDefinition skill) {
        if (skill == null) {
            return false;
        }
        if (skill.boundMcpServiceIds() != null && !skill.boundMcpServiceIds().isEmpty()) {
            return true;
        }
        if (skill.boundMcpToolNames() != null && !skill.boundMcpToolNames().isEmpty()) {
            return true;
        }
        return skill.toolConfigs() != null && skill.toolConfigs().stream()
            .anyMatch(config -> config != null
                && (config.enabled() == null || config.enabled())
                && config.toolName() != null
                && !config.toolName().isBlank());
    }
}
