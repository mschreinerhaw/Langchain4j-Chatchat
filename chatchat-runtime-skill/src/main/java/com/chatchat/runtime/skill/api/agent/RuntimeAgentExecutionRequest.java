package com.chatchat.runtime.skill.api.agent;

import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.workflow.ResolvedWorkflow;
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
