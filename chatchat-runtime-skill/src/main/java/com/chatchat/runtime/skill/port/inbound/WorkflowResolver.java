package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillRoleContext;
import com.chatchat.runtime.skill.api.WorkflowResolution;

import java.util.Map;

public interface WorkflowResolver {
    WorkflowResolution resolve(ResolvedSkill skill, AuthorizedSkillScope scope,
                               SkillRoleContext roleContext, Map<String, Object> intent);
}
