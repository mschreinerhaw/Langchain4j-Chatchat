package com.chatchat.runtime.skill.api.execution;

import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import java.util.List;
import java.util.Map;

public record SkillExecutionRequest(String query, SkillRoleContext roleContext,
                                    List<String> requestedSkillIds, int candidateLimit,
                                    String engine, Map<String, Object> intent,
                                    Map<String, Object> attributes) {
    public SkillExecutionRequest {
        query = query == null ? "" : query.trim();
        if (roleContext == null) throw new IllegalArgumentException("roleContext is required");
        requestedSkillIds = requestedSkillIds == null ? List.of() : List.copyOf(requestedSkillIds);
        candidateLimit = Math.max(1, Math.min(50, candidateLimit));
        engine = engine == null ? "" : engine.trim();
        intent = intent == null ? Map.of() : Map.copyOf(intent);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
