package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.model.InteractionSource;
import com.chatchat.chat.interaction.service.ConversationMemoryService;
import com.chatchat.chat.interaction.service.InteractionModeHandler;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.AgentRuntimePolicy;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeRuntimePort;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeSourceReference;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes a maintained Agent as a role-based model conversation.
 *
 * <p>This path deliberately bypasses Agent planning, MCP selection, tool execution and
 * evidence completion. Bound knowledge documents may still be retrieved directly as
 * prompt context; document retrieval is a Runtime context capability, not an MCP call.</p>
 */
@Component
@Slf4j
public class RoleChatModeHandler implements InteractionModeHandler {

    private static final int DEFAULT_KNOWLEDGE_TOKEN_BUDGET = 1200;

    private final ChatModel defaultChatModel;
    private final ConfigurableChatModelFactory chatModelFactory;
    private final SkillCatalogService skillCatalogService;
    private final KnowledgeRuntimePort knowledgeRuntime;

    public RoleChatModeHandler(ChatModel defaultChatModel,
                               ConfigurableChatModelFactory chatModelFactory,
                               SkillCatalogService skillCatalogService,
                               KnowledgeRuntimePort knowledgeRuntime) {
        this.defaultChatModel = defaultChatModel;
        this.chatModelFactory = chatModelFactory;
        this.skillCatalogService = skillCatalogService;
        this.knowledgeRuntime = knowledgeRuntime;
    }

    @Override
    public InteractionMode mode() {
        return InteractionMode.ROLE_CHAT;
    }

    @Override
    public InteractionResponse handle(InteractionRequest request, InteractionContext context) {
        SkillDefinition skill = skillCatalogService.resolve(request.getSkillId());
        if (!InteractionMode.fromAgentConfiguration(skill.defaultMode()).isRoleConversation()) {
            throw new IllegalArgumentException(
                "Agent " + skill.id() + " is configured for tool-agent execution, not role_chat");
        }
        com.chatchat.common.knowledge.KnowledgeContext knowledge = retrieveKnowledge(request, skill);
        String prompt = buildPrompt(request, context, skill, knowledge.compiledContext());
        ChatModel model = resolveModel(request, skill);

        long startedAt = System.currentTimeMillis();
        log.info("roleChatModelRequest requestId={} conversationId={} skillId={} modelName={} promptChars={} knowledgeUsed={}",
            context.requestId(), context.conversationId(), skill.id(), resolvedModelName(request, skill),
            prompt.length(), knowledge.used());
        String answer = model.chat(prompt);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("handler", "RoleChatModeHandler");
        metadata.put("executionMode", "ROLE_CHAT");
        metadata.put("skillId", skill.id());
        metadata.put("modelName", resolvedModelName(request, skill));
        metadata.put("availableTools", List.of());
        metadata.put("requiredTools", List.of());
        metadata.put("toolPlanningSkipped", true);
        metadata.put("knowledgeRetrieval", knowledge.status());
        metadata.put("knowledgeUsed", knowledge.used());
        metadata.put("knowledgeTokens", knowledge.estimatedTokens());
        metadata.put("knowledgeTokenBudget", knowledge.maxTokens());
        metadata.put("knowledgeTruncated", knowledge.truncated());
        metadata.put("knowledgeSkillCount", knowledge.plan() == null ? 0 : knowledge.plan().skills().size());
        metadata.put("historyUsed", context.history() == null ? 0 : context.history().size());
        metadata.put("summaryUsed", hasText(context.conversationSummary()));
        metadata.put("modelLatencyMs", System.currentTimeMillis() - startedAt);

        return InteractionResponse.builder()
            .answer(answer)
            .sources(toSources(knowledge.sources()))
            .toolTraces(List.of())
            .metadata(metadata)
            .build();
    }

    private ChatModel resolveModel(InteractionRequest request, SkillDefinition skill) {
        String selected = resolvedModelName(request, skill);
        if (!hasText(selected)) {
            return defaultChatModel;
        }
        return chatModelFactory.create(selected);
    }

    private String resolvedModelName(InteractionRequest request, SkillDefinition skill) {
        if (skill != null && hasText(skill.modelName())) {
            return skill.modelName().trim();
        }
        return request != null && hasText(request.getModelName()) ? request.getModelName().trim() : "";
    }

    private String buildPrompt(InteractionRequest request,
                               InteractionContext context,
                               SkillDefinition skill,
                               String knowledgeContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are operating in ROLE_CHAT mode. Answer directly as the configured business role.\n")
            .append("Do not plan, select, request, simulate, or claim to have called MCP/API/SQL tools.\n")
            .append("Distinguish facts supplied by the user or knowledge context from assumptions. ")
            .append("If the supplied subject is unsuitable for the requested need, say so explicitly.\n\n");
        if (hasText(skill.label())) {
            prompt.append("Role name: ").append(skill.label().trim()).append('\n');
        }
        if (hasText(skill.description())) {
            prompt.append("Role responsibility: ").append(skill.description().trim()).append('\n');
        }
        appendList(prompt, "Business scenarios", skill.usageScenarios());
        appendList(prompt, "Role tags", skill.skillTags());
        if (hasText(skill.systemPrompt())) {
            prompt.append("\nMaintained role instructions:\n").append(skill.systemPrompt().trim()).append("\n");
        }
        if (hasText(request.getSystemPrompt())) {
            prompt.append("\nRequest-specific instructions:\n").append(request.getSystemPrompt().trim()).append("\n");
        }
        if (hasText(context.conversationSummary())) {
            prompt.append("\nConversation summary (continuity only):\n")
                .append(context.conversationSummary().trim()).append("\n");
        }
        if (context.history() != null && !context.history().isEmpty()) {
            prompt.append("\nRecent conversation:\n")
                .append(context.history().stream()
                    .filter(message -> message != null && hasText(message.content()))
                    .map(this::formatHistoryMessage)
                    .collect(Collectors.joining("\n")))
                .append("\n");
        }
        if (hasText(knowledgeContext)) {
            prompt.append("\n<domain_knowledge>\n")
                .append(knowledgeContext.trim())
                .append("\n</domain_knowledge>\n")
                .append("Use this context for definitions, rules and interpretation; do not treat examples as current facts.\n");
        }
        String responseContract = responseContract(request);
        if (hasText(responseContract)) {
            prompt.append("\nResponse contract:\n").append(responseContract).append("\n");
        }
        prompt.append("\nCurrent user question:\n").append(request.getQuery());
        return prompt.toString();
    }

    private com.chatchat.common.knowledge.KnowledgeContext retrieveKnowledge(
        InteractionRequest request, SkillDefinition skill) {
        List<String> documentIds = clean(skill.boundDocumentIds());
        List<String> documentTags = clean(skill.boundDocumentTags());
        AgentRuntimePolicy runtimePolicy = AgentRuntimePolicy.from(
            skill.workflowConfig(), DEFAULT_KNOWLEDGE_TOKEN_BUDGET);
        int knowledgeTokenBudget = runtimePolicy.knowledgeTokenBudget();
        if (documentIds.isEmpty() && documentTags.isEmpty()) {
            return com.chatchat.common.knowledge.KnowledgeContext.empty(
                "not_configured", knowledgeTokenBudget);
        }
        try {
            return knowledgeRuntime.retrieveKnowledge(new KnowledgeRequest(
                KnowledgeRequest.SCHEMA_VERSION, request.getQuery(), "ROLE_CHAT",
                knowledgeTokenBudget,
                new KnowledgeScope(skill.id(), request.getTenantId(), request.getUserId(),
                    documentIds, documentTags, List.of()),
                null, Map.of("modelName", resolvedModelName(request, skill), "executionMode", "ROLE_CHAT",
                    "knowledgeSkillTimeoutMs", runtimePolicy.knowledgeSkillTimeoutMs())));
        } catch (RuntimeException ex) {
            log.warn("roleChatKnowledgeRetrievalFailed skillId={} error={}", skill.id(), ex.getMessage());
            return com.chatchat.common.knowledge.KnowledgeContext.empty(
                "failed", knowledgeTokenBudget);
        }
    }

    private List<InteractionSource> toSources(List<KnowledgeSourceReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<InteractionSource> sources = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            KnowledgeSourceReference reference = references.get(index);
            sources.add(InteractionSource.builder()
                .rank(index + 1)
                .source(hasText(reference.documentName()) ? reference.documentName() : reference.documentId())
                .snippet(reference.citation())
                .build());
        }
        return List.copyOf(sources);
    }

    private String responseContract(InteractionRequest request) {
        if (request == null || request.getToolInput() == null) return "";
        Object contract = request.getToolInput().get("responseContract");
        if (contract instanceof Map<?, ?> map) {
            Object value = map.get("prompt");
            return value == null ? "" : String.valueOf(value).trim();
        }
        return contract == null ? "" : String.valueOf(contract).trim();
    }

    private String formatHistoryMessage(ConversationMemoryService.MessageSnapshot message) {
        String role = hasText(message.role()) ? message.role().trim() : "unknown";
        return role + ": " + message.content().trim();
    }

    private void appendList(StringBuilder prompt, String label, List<String> values) {
        List<String> cleaned = clean(values);
        if (!cleaned.isEmpty()) prompt.append(label).append(": ").append(cleaned).append('\n');
    }

    private List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(this::hasText).map(String::trim).distinct().toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
