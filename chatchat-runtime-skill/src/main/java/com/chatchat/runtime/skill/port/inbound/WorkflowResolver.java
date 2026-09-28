package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.workflow.WorkflowResolution;

import java.util.Map;

public interface WorkflowResolver {
    WorkflowResolution resolve(ResolvedSkill skill, AuthorizedSkillScope scope,
                               SkillRoleContext roleContext, Map<String, Object> intent);
}
