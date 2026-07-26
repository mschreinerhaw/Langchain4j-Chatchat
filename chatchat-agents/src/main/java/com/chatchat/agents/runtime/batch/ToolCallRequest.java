package com.chatchat.agents.runtime.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolCallRequest(
    String callId,
    String toolName,
    Map<String, Object> arguments,
    Boolean emptyResultIsSuccess,
    List<String> requiredFields,
    ToolEvidencePolicy evidencePolicy
) {
    public ToolCallRequest(String callId, String toolName, Map<String, Object> arguments) {
        this(callId, toolName, arguments, null, List.of(), ToolEvidencePolicy.empty());
    }

    public ToolCallRequest(String callId,
                           String toolName,
                           Map<String, Object> arguments,
                           Boolean emptyResultIsSuccess) {
        this(callId, toolName, arguments, emptyResultIsSuccess, List.of(), ToolEvidencePolicy.empty());
    }

    public ToolCallRequest(String callId,
                           String toolName,
                           Map<String, Object> arguments,
                           Boolean emptyResultIsSuccess,
                           List<String> requiredFields) {
        this(callId, toolName, arguments, emptyResultIsSuccess, requiredFields, ToolEvidencePolicy.empty());
    }

    public ToolCallRequest {
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        requiredFields = requiredFields == null ? List.of() : requiredFields.stream()
            .filter(field -> field != null && !field.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        evidencePolicy = evidencePolicy == null ? ToolEvidencePolicy.empty() : evidencePolicy;
        if (evidencePolicy.requiredMetrics().isEmpty() && !requiredFields.isEmpty()) {
            evidencePolicy = new ToolEvidencePolicy(
                evidencePolicy.purpose(),
                evidencePolicy.healthCapability(),
                requiredFields,
                evidencePolicy.timeSemantics(),
                evidencePolicy.requiresContext(),
                evidencePolicy.freshnessMaxAgeSeconds()
            );
        }
    }
}
