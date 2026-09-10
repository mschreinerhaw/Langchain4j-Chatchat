package com.chatchat.common.knowledge;

import java.util.Comparator;
import java.util.List;

/** Validated dynamic skill plan. */
public record KnowledgeSkillPlan(
    String schemaVersion,
    String taskType,
    List<KnowledgeSkillInstance> skills,
    int tokenBudget
) {
    public static final String SCHEMA_VERSION = "knowledge_skill_plan.v1";
    public static final int MAX_SKILLS = 8;

    public KnowledgeSkillPlan {
        schemaVersion = SCHEMA_VERSION;
        taskType = taskType == null || taskType.isBlank() ? "GENERAL" : taskType.trim();
        skills = skills == null ? List.of() : skills.stream()
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingInt(KnowledgeSkillInstance::priority))
            .limit(MAX_SKILLS).toList();
        if (tokenBudget <= 0) throw new IllegalArgumentException("tokenBudget must be positive");
        long allocated = skills.stream().mapToLong(KnowledgeSkillInstance::tokenBudget).sum();
        if (allocated > tokenBudget) {
            throw new IllegalArgumentException("Knowledge skill budgets exceed plan token budget");
        }
    }
}
