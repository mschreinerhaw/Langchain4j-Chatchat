package com.chatchat.runtime.skill.api.execution;

import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.api.discovery.SkillRouteResult;
import com.chatchat.runtime.skill.api.resolution.SkillResolution;
import com.chatchat.runtime.skill.api.workflow.WorkflowResolution;
import java.util.Map;

public record SkillExecutionResult(String status, SkillRouteResult route,
                                   SkillResolution resolution,
                                   WorkflowResolution workflow,
                                   RuntimeAgentExecutionResult execution,
                                   Map<String, Object> diagnostics) {
    public SkillExecutionResult {
        status = status == null ? "" : status.trim();
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
