package com.chatchat.runtime.skill.api;

import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.spi.WorkflowResolver;

import java.util.Map;

public record SkillExecutionResult(String status, SkillRouteResult route,
                                   SkillResolution resolution,
                                   WorkflowResolver.WorkflowResolution workflow,
                                   AgentRuntimeAdapter.ExecutionResult execution,
                                   Map<String, Object> diagnostics) {
    public SkillExecutionResult {
        status = status == null ? "" : status.trim();
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
