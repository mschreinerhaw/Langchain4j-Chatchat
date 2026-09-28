package com.chatchat.runtime.skill.api;

import java.util.List;
import java.util.Map;

/** Persisted workflow selection resolved for one authorized Skill execution. */
public record ResolvedWorkflow(
    String workflowId,
    WorkflowType type,
    List<String> requiredCapabilities,
    Map<String, Object> configuration
) {
    public ResolvedWorkflow {
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }
}
