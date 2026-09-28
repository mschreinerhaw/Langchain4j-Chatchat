package com.chatchat.runtime.skill.api.skill;

import java.util.List;

/** Resource requirements declared by a Skill; declarations never grant access. */
public record SkillRequirements(List<String> documentIds, List<String> knowledgeBaseIds,
                                List<String> mcpToolIds, List<String> agentIds,
                                List<String> workflowIds, List<SkillDataRequirement> data) {
    public SkillRequirements {
        documentIds = immutable(documentIds);
        knowledgeBaseIds = immutable(knowledgeBaseIds);
        mcpToolIds = immutable(mcpToolIds);
        agentIds = immutable(agentIds);
        workflowIds = immutable(workflowIds);
        data = data == null ? List.of() : List.copyOf(data);
        if (data.size() > 8 || data.stream().map(SkillDataRequirement::id).distinct().count() != data.size())
            throw new IllegalArgumentException("Declare at most eight data requirements with unique ids");
    }

    public SkillRequirements(List<String> documentIds, List<String> knowledgeBaseIds,
                             List<String> mcpToolIds, List<String> agentIds, List<String> workflowIds) {
        this(documentIds, knowledgeBaseIds, mcpToolIds, agentIds, workflowIds, List.of());
    }

    public static SkillRequirements empty() {
        return new SkillRequirements(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }
}
