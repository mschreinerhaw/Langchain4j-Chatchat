package com.chatchat.agents.runtime.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hands an unambiguous reviewed binding to its execution step without another model extraction. */
final class ReviewedTemplateParameterProtocol {
    static Map<String, Object> bind(InterpretationPlanRuntime.StepExecution source,
                                    String templateId, Integer executionStepId) {
        if (source == null || !source.success() || executionStepId == null || templateId == null
            || !Boolean.TRUE.equals(source.metadata().get("semanticCandidateReviewSatisfied"))
            || !(source.metadata().get("parameterProtocols") instanceof List<?> protocols)) return Map.of();
        List<?> matching = protocols.stream().filter(value -> value instanceof Map<?, ?> protocol
            && templateId.equalsIgnoreCase(String.valueOf(protocol.get("template_id")))
            && "READY".equals(protocol.get("binding_status")) && !protocol.containsKey("binding_error")).toList();
        if (matching.size() != 1) return Map.of();
        Map<String, Object> bound = new LinkedHashMap<>();
        ((Map<?, ?>) matching.get(0)).forEach((key, value) -> bound.put(String.valueOf(key), value));
        bound.put("step_id", executionStepId);
        bound.put("runtime_binding_owner", "reviewed_template_discovery");
        bound.put("source_discovery_step_id", source.stepId());
        return Map.copyOf(bound);
    }
}
