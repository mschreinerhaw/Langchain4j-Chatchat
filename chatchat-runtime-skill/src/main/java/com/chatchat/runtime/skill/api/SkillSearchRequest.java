package com.chatchat.runtime.skill.api;

import java.util.List;
import java.util.Map;

public record SkillSearchRequest(String query, SkillRoleContext roleContext,
                                 List<String> requestedSkillIds, int limit,
                                 Map<String, Object> attributes) {
    public SkillSearchRequest {
        query = query == null ? "" : query.trim();
        if (roleContext == null) throw new IllegalArgumentException("roleContext is required");
        requestedSkillIds = requestedSkillIds == null ? List.of() : requestedSkillIds.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        limit = Math.max(1, Math.min(100, limit));
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
