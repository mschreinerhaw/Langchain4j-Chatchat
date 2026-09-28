package com.chatchat.runtime.skill.api.skill;

import java.util.List;
import java.util.Map;

/** Business data declaration. Parameter values are input names, never expressions or tool calls. */
public record SkillDataRequirement(String id, String contractId, List<String> requiredFor,
                                   boolean optional, Map<String, String> parameters) {
    public SkillDataRequirement {
        if (id == null || id.isBlank() || contractId == null || !contractId.matches("[a-zA-Z0-9_.-]+\\.v[1-9][0-9]*"))
            throw new IllegalArgumentException("Data requirement needs an id and versioned contractId");
        requiredFor = requiredFor == null ? List.of() : List.copyOf(requiredFor);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        if (parameters.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank()))
            throw new IllegalArgumentException("Data parameter bindings must name request inputs");
    }
}
