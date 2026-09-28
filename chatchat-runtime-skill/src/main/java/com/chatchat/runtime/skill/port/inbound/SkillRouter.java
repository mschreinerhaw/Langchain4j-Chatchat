package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.SkillRouteResult;
import com.chatchat.runtime.skill.api.SkillSearchRequest;

public interface SkillRouter {
    SkillRouteResult route(SkillSearchRequest request);
}
