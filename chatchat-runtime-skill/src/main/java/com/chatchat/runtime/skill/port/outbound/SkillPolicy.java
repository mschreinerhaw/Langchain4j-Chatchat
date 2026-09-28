package com.chatchat.runtime.skill.port.outbound;

import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;

/** Authorization is evaluated from the database relationship model, independently of Skill content. */
public interface SkillPolicy {
    boolean canDiscover(SkillRoleContext context, SkillDescriptor descriptor);
    AuthorizedSkillScope authorize(SkillRoleContext context, ResolvedSkill skill);
}
