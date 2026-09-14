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
import java.util.regex.Pattern;

/** Model-driven dynamic Knowledge Skill planner with contract validation and a safe fallback. */
@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class ModelDrivenKnowledgeSkillSynthesizer implements KnowledgeSkillSynthesizerPort {

    private static final int MAX_SKILLS = 6;
    private static final int MAX_DOMAIN_LENGTH = 200;
    private static final int MAX_GOAL_LENGTH = 500;
    private static final int MAX_HINT_LENGTH = 200;
    private static final Pattern STRUCTURAL_OR_INSTRUCTION_CONTENT = Pattern.compile(
        "(?is)(</?[a-z][^>]*>|```|https?://|(?:ignore|disregard|override)\\s+(?:all\\s+)?"
            + "(?:previous|prior|above)\\s+(?:instructions?|prompts?)|(?:act|behave)\\s+as\\b|"
            + "(?:system|developer|assistant)\\s*(?:message|prompt|instruction)|"
            + "忽略.{0,16}(?:指令|提示|要求)|(?:覆盖|绕过).{0,16}(?:指令|规则|限制)|越狱)"
    );
    private static final Pattern EXECUTABLE_CONTENT = Pattern.compile(
        "(?is)(?:^|[;\\n])\\s*(?:select|insert|update|delete|drop|alter|create|exec(?:ute)?)\\s+"
            + "|(?:tool|function)[ _-]?call\\s*[:=(]"
    );

    private final ChatModel defaultChatModel;
    private final ConfigurableChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final DefaultKnowledgeSkillSynthesizer fallback;

    @Override
    public KnowledgeSkillPlan synthesize(KnowledgeRequest request) {
        if (Boolean.TRUE.equals(request.attributes().get("preferDeterministicPlan"))) {
            log.debug("knowledgeSkillSynthesisDeterministic taskType={} reason=governed_bound_scope",
                request.taskType());
            return fallback.synthesize(request);
        }
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
            - Select at most 6 skills and only from the allowed list.
            - Priority 1 is highest; larger numbers are lower priority.
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
            if (planned.size() >= Math.min(MAX_SKILLS, request.maxTokens())) break;
            KnowledgeSkillType type = parseType(node.path("skillType").asText(), request);
            String goal = validatedText(node.path("goal").asText(""), "goal", MAX_GOAL_LENGTH, false);
            if (goal.isBlank()) continue;
            List<String> hints = new ArrayList<>();
            node.path("queryHints").forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    hints.add(validatedText(value.asText(), "queryHint", MAX_HINT_LENGTH, false));
                }
            });
            planned.add(new PlannedSkill(type,
                validatedText(node.path("domain").asText(), "domain", MAX_DOMAIN_LENGTH, true), goal,
                hints.stream().distinct().limit(6).toList(),
                Math.min(100, Math.max(1, node.path("priority").asInt(planned.size() + 1)))));
        }
        if (planned.isEmpty()) throw new IllegalArgumentException("no valid knowledge skills");
        int remainder = request.maxTokens();
        double remainingWeight = planned.stream().mapToDouble(this::priorityWeight).sum();
        List<KnowledgeSkillInstance> instances = new ArrayList<>();
        for (int index = 0; index < planned.size(); index++) {
            PlannedSkill skill = planned.get(index);
            double weight = priorityWeight(skill);
            int skillsAfter = planned.size() - index - 1;
            int budget = skillsAfter == 0 ? remainder
                : Math.max(1, (int) Math.floor(remainder * weight / remainingWeight));
            budget = Math.min(budget, remainder - skillsAfter);
            remainder -= budget;
            remainingWeight -= weight;
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

    private double priorityWeight(PlannedSkill skill) {
        return 1.0D / skill.priority();
    }

    private String validatedText(String value, String field, int maxLength, boolean defaultGeneral) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) return defaultGeneral ? "general" : "";
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
        boolean invalidControl = text.codePoints().anyMatch(codePoint ->
            Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t');
        if (invalidControl || STRUCTURAL_OR_INSTRUCTION_CONTENT.matcher(text).find()
            || EXECUTABLE_CONTENT.matcher(text).find()) {
            throw new IllegalArgumentException(field + " contains disallowed instruction or executable content");
        }
        return text;
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

    private record PlannedSkill(KnowledgeSkillType type, String domain, String goal,
                                List<String> hints, int priority) {
    }
}
