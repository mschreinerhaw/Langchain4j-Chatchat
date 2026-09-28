package com.chatchat.runtime.skill.port.outbound;

import com.chatchat.runtime.skill.api.execution.SkillDataResult;
import com.chatchat.runtime.skill.api.execution.SkillStepResult;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.skill.SkillAnalysisStep;

public interface SkillAnalysisOperator {
    boolean supports(String operator);
    SkillStepResult execute(SkillAnalysisStep step, SkillDataResult data, SkillRoleContext identity);
}
