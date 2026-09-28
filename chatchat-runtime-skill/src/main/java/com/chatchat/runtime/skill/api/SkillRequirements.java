package com.chatchat.runtime.skill.api;

import java.util.List;

/** Resource requirements declared by a Skill; declarations never grant access. */
public record SkillRequirements(List<String> documentIds, List<String> knowledgeBaseIds,
                                List<String> mcpToolIds, List<String> agentIds,
                                List<String> workflowIds) {
    public SkillRequirements {
        documentIds = immutable(documentIds);
        knowledgeBaseIds = immutable(knowledgeBaseIds);
        mcpToolIds = immutable(mcpToolIds);
        agentIds = immutable(agentIds);
        workflowIds = immutable(workflowIds);
    }

    public static SkillRequirements empty() {
        return new SkillRequirements(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }
}
