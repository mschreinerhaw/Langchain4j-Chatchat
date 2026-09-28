package com.chatchat.runtime.skill.spi;

import com.chatchat.runtime.skill.api.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.SkillExecutionResult;

public interface SkillRuntime {
    SkillExecutionResult execute(SkillExecutionRequest request);
}
