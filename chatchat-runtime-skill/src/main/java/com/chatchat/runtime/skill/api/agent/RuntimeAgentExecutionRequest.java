package com.chatchat.runtime.skill.api;

import java.util.Map;

/** Engine-neutral request passed from a resolved Skill workflow to an Agent runtime adapter. */
public record RuntimeAgentExecutionRequest(
    String engine,
    String query,
    SkillRoleContext roleContext,
    ResolvedSkill skill,
    AuthorizedSkillScope scope,
    ResolvedWorkflow workflow,
    Map<String, Object> attributes
) {
    public RuntimeAgentExecutionRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
