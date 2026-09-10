package com.chatchat.common.knowledge;

import java.util.List;
import java.util.Map;

/** Provider-neutral result from one Knowledge Skill instance. */
public record KnowledgeSkillResult(
    String instanceId,
    KnowledgeSkillType skillType,
    List<KnowledgeIR> knowledgeUnits,
    String status,
    Map<String, Object> metadata
) {
    public KnowledgeSkillResult {
        if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("instanceId is required");
        if (skillType == null) throw new IllegalArgumentException("skillType is required");
        knowledgeUnits = knowledgeUnits == null ? List.of() : List.copyOf(knowledgeUnits);
        status = status == null || status.isBlank() ? "empty" : status.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static KnowledgeSkillResult empty(KnowledgeSkillInstance skill, String status) {
        return new KnowledgeSkillResult(skill.instanceId(), skill.skillType(), List.of(), status, Map.of());
    }
}
