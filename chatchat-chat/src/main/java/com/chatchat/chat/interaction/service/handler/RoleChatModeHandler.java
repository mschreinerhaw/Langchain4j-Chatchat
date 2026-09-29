package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.model.InteractionSource;
import com.chatchat.chat.interaction.service.ConversationMemoryService;
import com.chatchat.chat.interaction.service.InteractionModeHandler;
import com.chatchat.chat.interaction.service.InteractionExecution;
import com.chatchat.chat.interaction.service.WorkflowEntryPlan;
import com.chatchat.common.runtime.capability.WorkflowOutcome;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.domain.planning.DomainSkillPlanningRouter;
import com.chatchat.chat.skills.runtime.AgentRuntimePolicy;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.spi.KnowledgeRuntimePort;
import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.model.KnowledgeSourceReference;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes a maintained Agent as a role-based model conversation.
 *
 * <p>This path answers as the configured role and bypasses Agent tool planning, MCP selection, tool execution and
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
    @Autowired(required = false)
    private DomainSkillRuntimePort domainSkillRuntime;
    @Autowired(required = false)
    private DomainSkillPlanningRouter domainSkillPlanningRouter;
    @Autowired(required = false)
    private SkillExecutionScopePort skillExecutionScope;

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
        var execution = new InteractionExecution(request);
        var response = execution.call("ROLE_CONVERSATION", () -> converse(request, context));
        return execution.complete(response, WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.ROLE_CONVERSATION, "CONFIGURED_ROLE_CONVERSATION"));
    }

    private InteractionResponse converse(InteractionRequest request, InteractionContext context) {
        SkillDefinition skill = skillCatalogService.resolve(request.getSkillId());
        if (!InteractionMode.fromAgentConfiguration(skill.defaultMode()).isRoleConversation()) {
            throw new IllegalArgumentException(
                "Agent " + skill.id() + " is configured for tool-agent execution, not role_chat");
        }
        SkillExecutionScopePort.EffectiveScope effectiveScope = resolveSkillScope(request, skill);
        // Role-chat applies domain skills as context; it never starts a competing analysis runtime.
        com.chatchat.common.knowledge.runtime.KnowledgeContext knowledge = retrieveKnowledge(request, skill, effectiveScope);
        List<String> configuredDomainSkillIds = configuredDomainSkillIds(skill);
        List<DomainSkillRuntimePort.DomainSkillContent> domainSkills = resolveDomainSkills(
            request, skill, effectiveScope.roles(), configuredDomainSkillIds);
        DomainSkillPlanningRouter.RoutingResult domainSkillRouting = domainSkillPlanningRouter == null
            ? null : domainSkillPlanningRouter.route(request.getQuery(), resolvedModelName(request, skill), domainSkills);
        String prompt = buildPrompt(request, context, skill, knowledge, domainSkills, domainSkillRouting);
        ChatModel model = resolveModel(request, skill);

        long startedAt = System.currentTimeMillis();
        log.info("roleChatModelRequest requestId={} conversationId={} skillId={} modelName={} promptChars={} knowledgeUsed={}",
            context.requestId(), context.conversationId(), skill.id(), resolvedModelName(request, skill),
            prompt.length(), knowledge.used());
        InteractionExecution.checkCancellation(request);
        String answer = model.chat(prompt);
        InteractionExecution.checkCancellation(request);

        Map<String, Object> metadata = new LinkedHashMap<>();
        boolean usable = answer != null && !answer.isBlank();
        metadata.put(WorkflowOutcome.METADATA_KEY, new WorkflowOutcome(
            usable ? WorkflowOutcome.Type.READY_TO_ANSWER : WorkflowOutcome.Type.FAILED,
            usable ? "ROLE_CONVERSATION_COMPLETED" : "EMPTY_ROLE_RESPONSE", List.of(), List.of(), usable));
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
        Map<String, Object> domainSkillProjection = domainSkillProjection(
            configuredDomainSkillIds, domainSkills, domainSkillRouting);
        metadata.put(DomainSkillRuntimePort.PLANNING_CONTEXT_ATTRIBUTE, domainSkillProjection);
        metadata.put("configuredDomainSkillCount", configuredDomainSkillIds.size());
        metadata.put("selectedDomainSkillCount", domainSkills.size());
        metadata.put("activatedDomainSkillCount", activatedDomainSkillCount(domainSkills, domainSkillRouting));
        metadata.put("domainSkillStatus", domainSkillProjection.get("status"));
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
                               com.chatchat.common.knowledge.runtime.KnowledgeContext knowledge,
                               List<DomainSkillRuntimePort.DomainSkillContent> domainSkills,
                               DomainSkillPlanningRouter.RoutingResult domainSkillRouting) {
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
        appendDomainSkills(prompt, domainSkills, domainSkillRouting);
        if (hasText(knowledge.compiledContext())) {
            prompt.append("\n<domain_knowledge>\n")
                .append(PromptBoundaryEscaper.escapeMarkupText(knowledge.compiledContext().trim()))
                .append("\n</domain_knowledge>\n")
                .append("Use this context for definitions, rules and interpretation; do not treat examples as current facts. ")
                .append("If entries conflict, report the conflict instead of silently choosing one.\n");
        } else if ("not_configured".equals(knowledge.status())) {
            prompt.append("\nKnowledge availability: no documents or knowledge bases are bound to this role. ")
                .append("Do not attempt or claim document retrieval, and do not emit a retrieval-failure or ")
                .append("missing-evidence notice merely because maintained instructions mention bound documents. ")
                .append("Answer from the role instructions, conversation, and user-provided facts only.\n");
        }
        String responseContract = responseContract(request);
        if (hasText(responseContract)) {
            prompt.append("\nResponse contract:\n").append(responseContract).append("\n");
        }
        prompt.append("\nCurrent user question:\n").append(request.getQuery());
        return prompt.toString();
    }

    private List<String> configuredDomainSkillIds(SkillDefinition skill) {
        if (skill == null || skill.workflowConfig() == null) return List.of();
        Object configured = skill.workflowConfig().get("boundDomainSkillIds");
        if (!(configured instanceof Iterable<?> values)) return List.of();
        List<String> ids = new ArrayList<>();
        values.forEach(value -> {
            if (value != null && !String.valueOf(value).isBlank()) ids.add(String.valueOf(value).trim());
        });
        return ids.stream().distinct().toList();
    }

    private List<DomainSkillRuntimePort.DomainSkillContent> resolveDomainSkills(
        InteractionRequest request, SkillDefinition skill, List<String> roles, List<String> ids) {
        if (domainSkillRuntime == null || ids.isEmpty()) return List.of();
        List<DomainSkillRuntimePort.DomainSkillContent> resolved = domainSkillRuntime.retrievePublishedForAgent(
            request.getTenantId(), request.getUserId(), roles, request.getQuery(), ids, skill.id());
        List<DomainSkillRuntimePort.DomainSkillContent> skills = resolved == null
            ? List.of() : resolved.stream().filter(item -> item != null).toList();
        log.info("roleChatDomainSkillsResolved skillId={} tenantId={} configuredCount={} "
                + "resolvedPublishedCount={} configuredIds={}",
            skill.id(), request.getTenantId(), ids.size(), skills.size(), ids);
        return skills;
    }

    private void appendDomainSkills(StringBuilder prompt,
                                    List<DomainSkillRuntimePort.DomainSkillContent> skills,
                                    DomainSkillPlanningRouter.RoutingResult routing) {
        if (routing != null) {
            if (hasText(routing.compiledContext())) {
                prompt.append("\n").append(PromptBoundaryEscaper.escapeMarkupText(routing.compiledContext()))
                    .append("\nApply this governed, task-specific Skill knowledge when answering.\n");
            }
            return;
        }
        if (skills == null || skills.isEmpty()) return;
        prompt.append("\n<domain_skills>\n");
        skills.forEach(item -> prompt.append("## ").append(item.name()).append(" [").append(item.category()).append("]\n")
            .append(PromptBoundaryEscaper.escapeMarkupText(item.markdownContent())).append("\n\n"));
        prompt.append("</domain_skills>\nApply these governed skill instructions when relevant to the request.\n");
    }

    private Map<String, Object> domainSkillProjection(
        List<String> configuredIds,
        List<DomainSkillRuntimePort.DomainSkillContent> selected,
        DomainSkillPlanningRouter.RoutingResult routing) {
        Map<String, Object> projection = new LinkedHashMap<>();
        if (routing != null) projection.putAll(domainSkillPlanningRouter.projection(routing));
        else {
            projection.put("schemaVersion", "domain_skill_planning.v2");
            projection.put("status", selected.isEmpty() ? "NO_CANDIDATES" : "DIRECT_APPLIED");
            projection.put("selectedCount", selected.size());
            projection.put("activatedCount", selected.size());
            projection.put("loadedCount", selected.size());
            projection.put("skills", skillCards(selected));
            projection.put("activatedSkills", skillCards(selected));
        }
        projection.put("configuredCount", configuredIds.size());
        projection.put("configuredSkillIds", configuredIds);
        return Map.copyOf(projection);
    }

    private List<Map<String, Object>> skillCards(List<DomainSkillRuntimePort.DomainSkillContent> skills) {
        if (skills == null) return List.of();
        return skills.stream().map(item -> {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", item.id() == null ? "" : item.id());
            card.put("name", item.name() == null ? "" : item.name());
            card.put("category", item.category() == null ? "" : item.category());
            return Map.copyOf(card);
        }).toList();
    }

    private int activatedDomainSkillCount(List<DomainSkillRuntimePort.DomainSkillContent> selected,
                                          DomainSkillPlanningRouter.RoutingResult routing) {
        return routing == null ? selected.size() : routing.activated().size();
    }

    private SkillExecutionScopePort.EffectiveScope resolveSkillScope(InteractionRequest request, SkillDefinition skill) {
        List<String> ids = clean(skill.boundDocumentIds());
        List<String> tags = clean(skill.boundDocumentTags());
        if (skillExecutionScope == null)
            return new SkillExecutionScopePort.EffectiveScope(ids, tags, List.of(), false, true);
        SkillExecutionScopePort.EffectiveScope effective = skillExecutionScope.resolve(
            request.getTenantId(), request.getUserId(), skill.id(), ids, tags);
        if (!effective.skillAllowed()) throw new SecurityException("Agent Skill is not authorized for this user");
        return effective;
    }

    private com.chatchat.common.knowledge.runtime.KnowledgeContext retrieveKnowledge(
        InteractionRequest request, SkillDefinition skill, SkillExecutionScopePort.EffectiveScope effectiveScope) {
        List<String> documentIds = effectiveScope.documentIds();
        List<String> documentTags = effectiveScope.tags();
        AgentRuntimePolicy runtimePolicy = AgentRuntimePolicy.from(
            skill.workflowConfig(), DEFAULT_KNOWLEDGE_TOKEN_BUDGET);
        int knowledgeTokenBudget = runtimePolicy.knowledgeTokenBudget();
        if (!effectiveScope.hasKnowledgeResources()) {
            return com.chatchat.common.knowledge.runtime.KnowledgeContext.empty(
                "not_configured", knowledgeTokenBudget);
        }
        try {
            return knowledgeRuntime.retrieveKnowledge(new KnowledgeRequest(
                KnowledgeRequest.SCHEMA_VERSION, request.getQuery(), "ROLE_CHAT",
                knowledgeTokenBudget,
                new KnowledgeScope(skill.id(), request.getTenantId(), request.getUserId(),
                    documentIds, documentTags, List.of(), effectiveScope.roles()),
                null, Map.of("modelName", resolvedModelName(request, skill), "executionMode", "ROLE_CHAT",
                    "skillScopeManaged", effectiveScope.managed(),
                    "knowledgeSkillTimeoutMs", runtimePolicy.knowledgeSkillTimeoutMs())));
        } catch (RuntimeException ex) {
            log.warn("roleChatKnowledgeRetrievalFailed skillId={} error={}", skill.id(), ex.getMessage());
            return com.chatchat.common.knowledge.runtime.KnowledgeContext.empty(
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
