package com.chatchat.runtime.skill.port.inbound;

import com.chatchat.runtime.skill.api.execution.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.execution.SkillExecutionResult;

public interface SkillRuntime {
    SkillExecutionResult execute(SkillExecutionRequest request);
}
