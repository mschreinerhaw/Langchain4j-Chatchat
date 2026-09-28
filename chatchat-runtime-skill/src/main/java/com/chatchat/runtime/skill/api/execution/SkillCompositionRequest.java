package com.chatchat.runtime.skill.api.execution;

import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import java.util.*;

public record SkillCompositionRequest(String query, SkillRoleContext identity, List<String> capabilities,
        List<String> skillIds, Map<String, String> workflowIds, Map<String, Object> inputs,
        String engine, Map<String, Object> attributes, int maxSkills) {
    public SkillCompositionRequest {
        if (identity == null || query == null || query.isBlank() || query.length() > 4000)
            throw new IllegalArgumentException("Authenticated query is required");
        capabilities = capabilities == null ? List.of() : capabilities.stream().distinct().toList();
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        if (capabilities.size() > 32 || skillIds.size() > 50 || capabilities.stream().anyMatch(value ->
            value == null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,119}"))) throw new IllegalArgumentException("Invalid capability request");
        workflowIds = workflowIds == null ? Map.of() : Map.copyOf(workflowIds);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        engine = engine == null || engine.isBlank() ? "LANGCHAIN4J" : engine;
        maxSkills = Math.max(1, Math.min(8, maxSkills));
    }
}
