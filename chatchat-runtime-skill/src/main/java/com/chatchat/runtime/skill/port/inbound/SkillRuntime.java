package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.SkillExecutionResult;

public interface SkillRuntime {
    SkillExecutionResult execute(SkillExecutionRequest request);
}
