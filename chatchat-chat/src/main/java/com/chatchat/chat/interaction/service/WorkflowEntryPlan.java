package com.chatchat.chat.interaction.service;

import java.util.List;
import java.util.Map;

/** An entry capability snapshot assigns planning responsibility, not execution permission. */
public record WorkflowEntryPlan(Owner owner, String reason, List<Map<String, Object>> toolPurposes) {
    public enum Owner { DIRECT_ANSWER, GOVERNED_RUNTIME, PROBLEM_ANALYSIS, PROVIDED_PLAN, ROLE_CONVERSATION }

    public WorkflowEntryPlan {
        java.util.Objects.requireNonNull(owner);
        toolPurposes = toolPurposes.stream().map(Map::copyOf).toList();
    }

    public static WorkflowEntryPlan of(Owner owner, String reason) {
        return new WorkflowEntryPlan(owner, reason, List.of());
    }
}
