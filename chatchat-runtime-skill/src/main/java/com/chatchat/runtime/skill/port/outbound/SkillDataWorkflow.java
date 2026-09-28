package com.chatchat.runtime.skill.port.outbound;

import com.chatchat.runtime.skill.api.execution.SkillDataResult;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.SkillResolution;
import com.chatchat.runtime.skill.api.skill.SkillDataRequirement;
import java.util.Map;

/** Host-owned fixed acquisition workflow; imported instructions cannot register implementations. */
public interface SkillDataWorkflow {
    boolean supports(SkillDataRequirement requirement, SkillResolution skill, SkillRoleContext identity);
    SkillDataResult acquire(SkillDataRequirement requirement, SkillResolution skill,
                            SkillRoleContext identity, Map<String, Object> parameters);
}
