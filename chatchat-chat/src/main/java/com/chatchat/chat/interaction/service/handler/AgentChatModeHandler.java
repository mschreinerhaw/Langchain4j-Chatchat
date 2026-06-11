package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.AgentToolPolicyResolver;
import com.chatchat.chat.interaction.service.InteractionModeHandler;
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

    private static final int WEB_SEARCH_REFERENCE_LIMIT = 10;

    private final AgentOrchestrator agentOrchestrator;
    private final SkillCatalogService skillCatalogService;
    private final AgentToolPolicyResolver toolPolicyResolver;

    @Override
    public InteractionMode mode() {
        return InteractionMode.AGENT_CHAT;
    }

    @Override
    public InteractionResponse handle(InteractionRequest request, InteractionContext context) {
        SkillDefinition skill = skillCatalogService.resolve(request.getSkillId());
        AgentToolPolicyResolver.ToolPolicy toolPolicy = toolPolicyResolver.resolve(request, skill);
        String systemPrompt = resolveSystemPrompt(request, skill);
        String modelName = skill.modelName() != null && !skill.modelName().isBlank()
            ? skill.modelName()
            : request.getModelName();

        Map<String, Object> runtimeAttributes = runtimeAttributes(request, skill);
        AgentOrchestrator.AgentExecutionResult result = runtimeAttributes.isEmpty()
            ? agentOrchestrator.executeAgent(
                request.getQuery(),
                request.getTenantId(),
                toolPolicy.availableTools(),
                systemPrompt,
                modelName,
                skill.boundDocumentIds(),
                skill.boundDocumentTags(),
                request.getSkillId(),
                context.requestId(),
                context.conversationId(),
                request.getUserId(),
                resolveWebSearchResultLimit(request.getMaxResults()),
                toolPolicy.requiredTools(),
                toolPolicy.requireBoundToolCall()
            )
            : agentOrchestrator.executeAgent(
                request.getQuery(),
                request.getTenantId(),
                toolPolicy.availableTools(),
                systemPrompt,
                modelName,
                skill.boundDocumentIds(),
                skill.boundDocumentTags(),
                request.getSkillId(),
                context.requestId(),
                context.conversationId(),
                request.getUserId(),
                resolveWebSearchResultLimit(request.getMaxResults()),
                toolPolicy.requiredTools(),
                toolPolicy.requireBoundToolCall(),
                runtimeAttributes
            );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("availableTools", toolPolicy.availableTools());
        metadata.put("requiredTools", toolPolicy.requiredTools());
        metadata.put("toolIntents", toolPolicy.activatedIntents());
        metadata.put("hasMcpBinding", toolPolicy.hasMcpBinding());
        metadata.put("selectedCandidateTools", toolPolicy.selectedCandidateTools());
        metadata.put("skippedToolReasons", toolPolicy.skippedToolReasons());
        metadata.put("skillId", request.getSkillId() == null ? "general" : request.getSkillId());
        metadata.put("modelName", modelName);
        metadata.put("agent", result.metadata());
        metadata.put("handler", "AgentChatModeHandler");

        return InteractionResponse.builder()
            .answer(result.answer())
            .toolTraces(result.toolTraces())
            .metadata(metadata)
            .build();
    }

    private String resolveSystemPrompt(InteractionRequest request, SkillDefinition skill) {
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            return request.getSystemPrompt();
        }
        return skill.systemPrompt();
    }

    private int resolveWebSearchResultLimit(Integer maxResults) {
        if (maxResults == null) {
            return WEB_SEARCH_REFERENCE_LIMIT;
        }
        return Math.max(1, Math.min(WEB_SEARCH_REFERENCE_LIMIT, maxResults));
    }

    private Map<String, Object> runtimeAttributes(InteractionRequest request, SkillDefinition skill) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (request != null && request.getToolInput() != null && !request.getToolInput().isEmpty()) {
            Object confirmation = request.getToolInput().get("mcpConfirmation");
            if (confirmation instanceof Map<?, ?>) {
                attributes.put("mcpConfirmation", confirmation);
            }
            Object cancellation = request.getToolInput().get("__agentCancellation");
            if (cancellation != null) {
                attributes.put("__agentCancellation", cancellation);
            }
        }
        if (skill != null && skill.workflowConfig() != null && !skill.workflowConfig().isEmpty()) {
            attributes.put("mcpWorkflow", skill.workflowConfig());
        }
        return attributes.isEmpty() ? Map.of() : attributes;
    }
}
