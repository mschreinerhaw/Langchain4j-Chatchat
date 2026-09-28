package com.chatchat.runtime.skill.api;

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
