package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.discovery.SkillRouteResult;
import com.chatchat.runtime.skill.api.discovery.SkillSearchRequest;

public interface SkillRouter {
    SkillRouteResult route(SkillSearchRequest request);
}
