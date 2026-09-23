package com.chatchat.common.runtime.analysis.plan;

import com.chatchat.common.runtime.analysis.model.AnalysisCapability;

import java.util.Map;

public record PlanStep(String stepId, String operation, AnalysisCapability capability,
                       boolean required, Map<String, Object> parameters) {
    public PlanStep {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
