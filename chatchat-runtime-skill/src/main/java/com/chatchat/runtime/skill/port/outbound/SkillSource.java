package com.chatchat.runtime.skill.port.outbound;

import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.SkillResourceContent;
import com.chatchat.runtime.skill.api.SkillRoleContext;
import com.chatchat.runtime.skill.api.SkillSearchRequest;

import java.util.List;
import java.util.Optional;

/** Vendor-neutral source for PostgreSQL, classpath, MCP, REST or framework-provided skills. */
public interface SkillSource {
    String sourceId();
    default int priority() { return 0; }
    List<SkillDescriptor> search(SkillSearchRequest request);
    Optional<ResolvedSkill> resolve(SkillResolutionRequest request);
    default Optional<SkillResourceContent> readResource(String skillId, String resourceId,
                                                        SkillRoleContext context) {
        return Optional.empty();
    }
}
