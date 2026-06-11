package com.chatchat.agents.runtime;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.Map;

@Builder
public record ToolExecutionPlan(
    String workflow,
    String intent,
    String tool,
    String operationType,
    String riskLevel,
    Map<String, Object> parameters,
    String reason
) {

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workflow", workflow);
        values.put("intent", intent);
        values.put("tool", tool);
        values.put("operation_type", operationType);
        values.put("risk_level", riskLevel);
        values.put("parameters", parameters == null ? Map.of() : parameters);
        values.put("reason", reason);
        return values;
    }
}
