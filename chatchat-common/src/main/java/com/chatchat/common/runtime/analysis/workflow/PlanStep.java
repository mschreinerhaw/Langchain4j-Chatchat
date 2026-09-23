package com.chatchat.common.runtime.analysis.workflow;

import java.util.Map;

public record PlanStep(String stepId, String operation, AnalysisCapability capability,
                       boolean required, Map<String, Object> parameters) {
    public PlanStep {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
