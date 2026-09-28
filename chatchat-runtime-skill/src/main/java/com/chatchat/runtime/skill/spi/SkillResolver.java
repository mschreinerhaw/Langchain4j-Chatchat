package com.chatchat.runtime.skill.spi;

import com.chatchat.runtime.skill.api.SkillResolution;
import com.chatchat.runtime.skill.api.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.SkillResourceContent;
import com.chatchat.runtime.skill.api.SkillResourceRequest;

import java.util.Optional;

public interface SkillResolver {
    SkillResolution resolve(SkillResolutionRequest request);
    Optional<SkillResourceContent> readResource(SkillResourceRequest request);
}
