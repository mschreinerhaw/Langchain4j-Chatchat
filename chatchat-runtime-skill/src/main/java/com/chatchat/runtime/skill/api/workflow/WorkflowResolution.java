package com.chatchat.runtime.skill.api.workflow;

import java.util.Map;

/** Outcome of deterministic workflow resolution. */
public record WorkflowResolution(ResolvedWorkflow workflow, String status, Map<String, Object> diagnostics) {
    public WorkflowResolution {
        status = status == null || status.isBlank() ? "UNRESOLVED" : status.trim();
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public boolean resolved() {
        return workflow != null;
    }
}
