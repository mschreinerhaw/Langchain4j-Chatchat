package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.runtime.event.*;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.runtime.skill.api.execution.SkillCompositionRequest;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.application.SkillIntelligenceLayer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Opt-in Agent configuration reuses ordinary async tasks and their monitoring stream. */
@Component
public class SkillIntelligenceInteractionBridge {
    private final SkillIntelligenceLayer intelligence;
    private final ObjectProvider<AgentRunEventPublisher> publishers;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chatchat.runtime.skill.port.inbound.SkillRouter router;
    public SkillIntelligenceInteractionBridge(SkillIntelligenceLayer intelligence, ObjectProvider<AgentRunEventPublisher> publishers) {
        this.intelligence = intelligence; this.publishers = publishers;
    }
    private List<com.chatchat.runtime.skill.api.skill.SkillDescriptor> candidates(InteractionRequest request, SkillDefinition agent, List<String> roles) {
        var ids = strings(agent.workflowConfig() == null ? null : agent.workflowConfig().get("boundDomainSkillIds"));
        if (router == null || ids.isEmpty()) return List.of();
        return router.route(new com.chatchat.runtime.skill.api.discovery.SkillSearchRequest(request.getQuery(),
            new SkillRoleContext(request.getTenantId(), request.getUserId(), roles, List.of(), Map.of("agentId", agent.id())),
            ids, Math.min(50, ids.size()), Map.of())).candidates();
    }
    public boolean enabled(InteractionRequest request, SkillDefinition agent, List<String> roles) {
        if (agent.workflowConfig() != null && agent.workflowConfig().get("skillIntelligenceEngine") instanceof String engine && !engine.isBlank()) return true;
        return candidates(request, agent, roles).stream().anyMatch(item -> item.metadata().get("executionEngine") instanceof String engine && !engine.isBlank());
    }
    public InteractionResponse execute(InteractionRequest request, InteractionContext context, SkillDefinition agent, List<String> roles) {
        String engine = String.valueOf(agent.workflowConfig().getOrDefault("skillIntelligenceEngine", "LANGCHAIN4J")).trim().toUpperCase(Locale.ROOT);
        if (!Set.of("GOOGLE_ADK_NATIVE", "LANGCHAIN4J", "OPENAI_COMPATIBLE").contains(engine))
            throw new IllegalArgumentException("Skill Intelligence engine is not registered");
        String model = agent.modelName() == null || agent.modelName().isBlank() ? request.getModelName() : agent.modelName();
        // The first bound model plans the composition; each resolved skill selects its own execution binding.
        var planningModel = candidates(request, agent, roles).stream().map(item -> item.metadata().get("executionModel"))
            .filter(String.class::isInstance).map(String.class::cast).filter(value -> !value.isBlank()).findFirst();
        if (planningModel.isPresent()) model = planningModel.get();
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Analysis model is required");
        List<String> skills = strings(agent.workflowConfig().get("boundDomainSkillIds"));
        var inputs = new LinkedHashMap<String, Object>();
        if (request.getToolInput() != null && request.getToolInput().get("skillDataInputs") instanceof Map<?, ?> values) {
            if (values.size() > 32) throw new IllegalArgumentException("Too many analysis inputs");
            values.forEach((key, value) -> {
                if (!(key instanceof String name) || name.length() > 100 || !(value instanceof String || value instanceof Number || value instanceof Boolean)
                    || value.toString().length() > 1000) throw new IllegalArgumentException("Invalid analysis input");
                inputs.put(name, value);
            });
        }
        var runId = request.getToolInput() == null ? "" : String.valueOf(request.getToolInput().getOrDefault("__agentRunId", ""));
        var analysis = new SkillCompositionRequest(request.getQuery(), new SkillRoleContext(request.getTenantId(), request.getUserId(),
            roles, List.of(), Map.of("agentId", agent.id())), List.of(), skills, Map.of(), inputs, engine,
            Map.of("modelName", model, "maxSteps", 6, "maxToolCalls", 0,
                "allowDataAcquisition", context.mode() != InteractionMode.ROLE_CHAT
                    && !InteractionMode.fromAgentConfiguration(agent.defaultMode()).isRoleConversation()), 4);
        var result = intelligence.execute(analysis, event -> {
            if (!runId.isBlank()) publishers.orderedStream().forEach(publisher -> publisher.publish(AgentRunEvent.of(runId,
                AgentRunEventType.OBSERVATION_RECORDED, "Skill Intelligence: " + event.stage(),
                Map.of("stage", event.stage(), "round", event.round(), "skillId", event.skillId(), "status", event.status()))));
        });
        String answer = result.results().entrySet().stream().filter(entry -> entry.getValue().execution() != null)
            .map(entry -> "### " + entry.getKey() + "\n\n" + entry.getValue().execution().output())
            .filter(text -> !text.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
        if (answer.isBlank()) answer = "当前没有可执行的已授权分析技能或所需数据，请检查技能授权、数据契约和工作流绑定。";
        if (!"COMPLETED".equals(result.status())) answer += "\n\n分析存在限制：" + result.stopReason()
            + "。未完成能力：" + String.join("、", result.missingCapabilities());
        return InteractionResponse.builder().conversationId(context.conversationId()).requestId(context.requestId())
            .mode(context.mode() == InteractionMode.ROLE_CHAT ? "role_chat" : "agent_chat").answer(answer)
            .metadata(Map.of("skillIntelligence", result, "engine", "PER_SKILL_BINDING"))
            .latencyMs(result.elapsedMs()).timestamp(System.currentTimeMillis()).build();
    }
    private List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).distinct().toList() : List.of();
    }
}
