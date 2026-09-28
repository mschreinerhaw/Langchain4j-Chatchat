package com.chatchat.runtime.skill.spi;

import com.chatchat.runtime.skill.api.SkillRouteResult;
import com.chatchat.runtime.skill.api.SkillSearchRequest;

public interface SkillRouter {
    SkillRouteResult route(SkillSearchRequest request);
}
