package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeSkillSynthesizerPort;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Safe deterministic fallback when a model planner is unavailable or returns an invalid plan. */
@Component
public class DefaultKnowledgeSkillSynthesizer implements KnowledgeSkillSynthesizerPort {

    @Override
    public KnowledgeSkillPlan synthesize(KnowledgeRequest request) {
        List<KnowledgeSkillType> selected = selectTypes(request);
        int perSkill = Math.max(1, request.maxTokens() / selected.size());
        int remainder = request.maxTokens();
        List<KnowledgeSkillInstance> skills = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            KnowledgeSkillType type = selected.get(index);
            int budget = index == selected.size() - 1 ? remainder : perSkill;
            remainder -= budget;
            skills.add(new KnowledgeSkillInstance(
                "knowledge-" + (index + 1), type, firstDomain(request),
                goal(type, request.query()), List.of(request.query()), index + 1, budget, Map.of()));
        }
        return new KnowledgeSkillPlan(
            KnowledgeSkillPlan.SCHEMA_VERSION, request.taskType(), skills, request.maxTokens());
    }

    private List<KnowledgeSkillType> selectTypes(KnowledgeRequest request) {
        String text = (request.taskType() + " " + request.query()).toLowerCase(Locale.ROOT);
        List<KnowledgeSkillType> candidates = new ArrayList<>();
        if ("TOOL_ANALYSIS".equalsIgnoreCase(request.taskType())) {
            // A bound, governed document scope is already narrow. Cover the four knowledge
            // dimensions needed by analytical reports in parallel without another model call.
            candidates.add(KnowledgeSkillType.CONCEPT_LOOKUP);
            candidates.add(KnowledgeSkillType.METRIC_LOOKUP);
            candidates.add(KnowledgeSkillType.METHODOLOGY_LOOKUP);
            candidates.add(KnowledgeSkillType.CONSTRAINT_LOOKUP);
        }
        if (containsAny(text, "指标", "口径", "metric", "ratio", "比例", "集中度")) {
            candidates.add(KnowledgeSkillType.METRIC_LOOKUP);
        }
        if (containsAny(text, "风险", "规则", "判断", "阈值", "rule", "policy", "制度")) {
            candidates.add(KnowledgeSkillType.RULE_LOOKUP);
        }
        if (containsAny(text, "限制", "约束", "不得", "constraint", "边界")) {
            candidates.add(KnowledgeSkillType.CONSTRAINT_LOOKUP);
        }
        if (containsAny(text, "流程", "操作", "procedure", "步骤")) {
            candidates.add(KnowledgeSkillType.PROCEDURE_LOOKUP);
        }
        if (candidates.isEmpty()) {
            candidates.add(KnowledgeSkillType.CONCEPT_LOOKUP);
            candidates.add(KnowledgeSkillType.METHODOLOGY_LOOKUP);
        }
        List<KnowledgeSkillType> selected = candidates.stream().distinct()
            .filter(request.allowedSkillTypes()::contains).limit(4).toList();
        return selected.isEmpty() ? List.of(request.allowedSkillTypes().iterator().next()) : selected;
    }

    private String firstDomain(KnowledgeRequest request) {
        return request.scope().domains().isEmpty() ? "general" : request.scope().domains().get(0);
    }

    private String goal(KnowledgeSkillType type, String query) {
        return switch (type) {
            case METRIC_LOOKUP -> "获取任务涉及的指标定义与计算口径：" + query;
            case RULE_LOOKUP -> "获取任务涉及的业务判断规则：" + query;
            case CONSTRAINT_LOOKUP -> "获取结论适用限制与禁止事项：" + query;
            case PROCEDURE_LOOKUP -> "获取任务涉及的业务流程：" + query;
            case POLICY_INTERPRETATION -> "解释任务涉及的制度条款：" + query;
            case METHODOLOGY_LOOKUP -> "获取解决任务所需的分析方法：" + query;
            case EVIDENCE_EXPLANATION -> "获取解释证据所需的领域知识：" + query;
            case FAQ_LOOKUP -> "获取任务相关常见问答：" + query;
            case CONCEPT_LOOKUP -> "获取任务涉及的领域概念：" + query;
        };
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
