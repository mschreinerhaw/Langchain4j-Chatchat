package com.chatchat.chat.knowledge;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeSkillSynthesizerPort;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import com.chatchat.knowledgebase.runtime.DefaultKnowledgeSkillSynthesizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Model-driven dynamic Knowledge Skill planner with contract validation and a safe fallback. */
@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class ModelDrivenKnowledgeSkillSynthesizer implements KnowledgeSkillSynthesizerPort {

    private final ChatModel defaultChatModel;
    private final ConfigurableChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final DefaultKnowledgeSkillSynthesizer fallback;

    @Override
    public KnowledgeSkillPlan synthesize(KnowledgeRequest request) {
        try {
            String response = resolveModel(request).chat(plannerPrompt(request));
            return parseAndValidate(response, request);
        } catch (Exception ex) {
            log.warn("knowledgeSkillSynthesisFallback taskType={} error={}", request.taskType(), ex.getMessage());
            return fallback.synthesize(request);
        }
    }

    private ChatModel resolveModel(KnowledgeRequest request) {
        Object selected = request.attributes().get("modelName");
        return selected == null || String.valueOf(selected).isBlank()
            ? defaultChatModel : chatModelFactory.create(String.valueOf(selected).trim());
    }

    private String plannerPrompt(KnowledgeRequest request) {
        return """
            You are a Knowledge Skill planner. Decide what normalized domain knowledge is needed for the task.
            Return JSON only: {"skills":[{"skillType":"RULE_LOOKUP","domain":"...","goal":"...","queryHints":["..."],"priority":1}]}.
            Allowed skillType values: %s
            Rules:
            - Select at most 4 skills and only from the allowed list.
            - Generate goals and query hints, never code, SQL, URLs, tool calls, or document IDs.
            - Ask only for knowledge needed to solve the current task.
            - Include constraints when conclusions may be regulated, risky, or easily overstated.
            Task type: %s
            User task: %s
            Knowledge domains: %s
            """.formatted(request.allowedSkillTypes(), request.taskType(), request.query(), request.scope().domains());
    }

    private KnowledgeSkillPlan parseAndValidate(String raw, KnowledgeRequest request) throws Exception {
        JsonNode root = objectMapper.readTree(stripFence(raw));
        JsonNode nodes = root.path("skills");
        if (!nodes.isArray() || nodes.isEmpty()) throw new IllegalArgumentException("skills array is required");
        List<PlannedSkill> planned = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (planned.size() >= 4) break;
            KnowledgeSkillType type = parseType(node.path("skillType").asText(), request);
            String goal = node.path("goal").asText("").trim();
            if (goal.isBlank()) continue;
            List<String> hints = new ArrayList<>();
            node.path("queryHints").forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) hints.add(value.asText().trim());
            });
            planned.add(new PlannedSkill(type, clean(node.path("domain").asText()), goal,
                hints.stream().distinct().limit(6).toList(), Math.max(1, node.path("priority").asInt(planned.size() + 1))));
        }
        if (planned.isEmpty()) throw new IllegalArgumentException("no valid knowledge skills");
        int perSkill = Math.max(1, request.maxTokens() / planned.size());
        int remainder = request.maxTokens();
        List<KnowledgeSkillInstance> instances = new ArrayList<>();
        for (int index = 0; index < planned.size(); index++) {
            PlannedSkill skill = planned.get(index);
            int budget = index == planned.size() - 1 ? remainder : perSkill;
            remainder -= budget;
            instances.add(new KnowledgeSkillInstance(
                "model-knowledge-" + (index + 1), skill.type(), skill.domain(), skill.goal(),
                skill.hints(), skill.priority(), budget, Map.of("planner", "model")));
        }
        return new KnowledgeSkillPlan(
            KnowledgeSkillPlan.SCHEMA_VERSION, request.taskType(), instances, request.maxTokens());
    }

    private KnowledgeSkillType parseType(String value, KnowledgeRequest request) {
        KnowledgeSkillType type = KnowledgeSkillType.valueOf(value.trim().toUpperCase());
        if (!request.allowedSkillTypes().contains(type)) {
            throw new IllegalArgumentException("knowledge skill type is not allowed: " + type);
        }
        return type;
    }

    private String stripFence(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (!text.startsWith("```")) return text;
        int firstBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        return firstBreak >= 0 && lastFence > firstBreak
            ? text.substring(firstBreak + 1, lastFence).trim() : text;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "general" : value.trim();
    }

    private record PlannedSkill(KnowledgeSkillType type, String domain, String goal,
                                List<String> hints, int priority) {
    }
}
