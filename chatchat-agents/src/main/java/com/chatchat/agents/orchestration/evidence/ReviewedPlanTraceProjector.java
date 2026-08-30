package com.chatchat.agents.orchestration.evidence;

import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves a plan step's committed semantic review when its tool trace crosses workflow boundaries. */
public final class ReviewedPlanTraceProjector {

    private ReviewedPlanTraceProjector() {
    }

    public static InteractionToolTrace project(InteractionToolTrace original,
                                               Object reviewedOutput,
                                               Map<String, Object> stepMetadata,
                                               ObjectMapper objectMapper) {
        if (original == null || stepMetadata == null
            || !Boolean.TRUE.equals(stepMetadata.get("semanticCandidateReviewSatisfied"))) {
            return original;
        }
        Map<String, Object> runtimeMetadata = new LinkedHashMap<>(
            original.getRuntimeMetadata() == null ? Map.of() : original.getRuntimeMetadata());
        runtimeMetadata.putAll(stepMetadata);
        runtimeMetadata.put("reviewedPlanEvidence", true);
        return InteractionToolTrace.builder()
            .toolName(original.getToolName())
            .displayName(original.getDisplayName())
            .serviceId(original.getServiceId())
            .serviceName(original.getServiceName())
            .success(original.isSuccess())
            .input(original.getInput())
            .output(reviewedOutput == null ? original.getOutput() : stringify(reviewedOutput, objectMapper))
            .errorMessage(original.getErrorMessage())
            .durationMs(original.getDurationMs())
            .startedAt(original.getStartedAt())
            .finishedAt(original.getFinishedAt())
            .runtimeMetadata(runtimeMetadata)
            .build();
    }

    private static String stringify(Object value, ObjectMapper objectMapper) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
