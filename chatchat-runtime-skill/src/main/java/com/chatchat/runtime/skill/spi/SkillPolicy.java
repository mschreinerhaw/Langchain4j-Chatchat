package com.chatchat.runtime.skill.spi;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillRoleContext;

/** Authorization is evaluated from the database relationship model, independently of Skill content. */
public interface SkillPolicy {
    boolean canDiscover(SkillRoleContext context, SkillDescriptor descriptor);
    AuthorizedSkillScope authorize(SkillRoleContext context, ResolvedSkill skill);
}
