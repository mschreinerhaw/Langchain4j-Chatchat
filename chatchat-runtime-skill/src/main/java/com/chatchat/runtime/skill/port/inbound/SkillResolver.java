package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.resolution.SkillResolution;
import com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.resource.SkillResourceContent;
import com.chatchat.runtime.skill.api.resource.SkillResourceRequest;

import java.util.Optional;

public interface SkillResolver {
    SkillResolution resolve(SkillResolutionRequest request);
    Optional<SkillResourceContent> readResource(SkillResourceRequest request);
}
