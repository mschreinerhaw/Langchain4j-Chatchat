package com.chatchat.runtime.skill.spi;

import com.chatchat.runtime.skill.api.SkillResolution;
import com.chatchat.runtime.skill.api.SkillResolutionRequest;

public interface SkillResolver {
    SkillResolution resolve(SkillResolutionRequest request);
}
