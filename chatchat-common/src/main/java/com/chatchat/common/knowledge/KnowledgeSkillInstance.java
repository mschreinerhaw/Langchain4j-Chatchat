package com.chatchat.common.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A model-planned instance of a statically defined knowledge capability. */
public record KnowledgeSkillInstance(
    String instanceId,
    KnowledgeSkillType skillType,
    String domain,
    String goal,
    List<String> queryHints,
    int priority,
    int tokenBudget,
    Map<String, Object> parameters
) {
    public KnowledgeSkillInstance {
        instanceId = limited(required(instanceId, "instanceId"), 128);
        if (skillType == null) throw new IllegalArgumentException("skillType is required");
        domain = limited(clean(domain), 200);
        goal = limited(required(goal, "goal"), 1000);
        queryHints = queryHints == null ? List.of() : queryHints.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim).map(value -> limited(value, 200)).distinct().limit(8).toList();
        priority = Math.max(1, Math.min(priority, 100));
        if (tokenBudget <= 0) throw new IllegalArgumentException("tokenBudget must be positive");
        parameters = parameters == null || parameters.isEmpty()
            ? Map.of() : Map.copyOf(new LinkedHashMap<>(parameters));
    }

    private static String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) throw new IllegalArgumentException(field + " is required");
        return cleaned;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String limited(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
