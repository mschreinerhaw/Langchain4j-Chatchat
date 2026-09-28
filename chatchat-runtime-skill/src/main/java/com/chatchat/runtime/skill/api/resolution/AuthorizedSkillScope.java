package com.chatchat.runtime.skill.api.resolution;

import java.util.List;

/** Database-derived authorization result. SKILL.md declarations are never copied here directly. */
public record AuthorizedSkillScope(boolean skillAllowed, List<String> documentIds,
                                   List<String> knowledgeBaseIds, List<String> mcpToolIds,
                                   List<String> agentIds, List<String> workflowIds,
                                   List<String> denialReasons) {
    public AuthorizedSkillScope {
        documentIds = immutable(documentIds);
        knowledgeBaseIds = immutable(knowledgeBaseIds);
        mcpToolIds = immutable(mcpToolIds);
        agentIds = immutable(agentIds);
        workflowIds = immutable(workflowIds);
        denialReasons = immutable(denialReasons);
    }

    public static AuthorizedSkillScope denied(String reason) {
        return new AuthorizedSkillScope(false, List.of(), List.of(), List.of(), List.of(), List.of(),
            reason == null || reason.isBlank() ? List.of("SKILL_ACCESS_DENIED") : List.of(reason));
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }
}
