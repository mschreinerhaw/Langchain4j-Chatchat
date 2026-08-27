package com.chatchat.common.interaction;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects backend tool evidence into a data-free user-facing execution receipt. */
public final class UserFacingToolTraceProjector {

    private UserFacingToolTraceProjector() {
    }

    public static List<InteractionToolTrace> project(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        return traces.stream()
            .filter(Objects::nonNull)
            .map(trace -> InteractionToolTrace.builder()
                .toolName(trace.getToolName())
                .displayName(trace.getDisplayName())
                .serviceId(trace.getServiceId())
                .serviceName(trace.getServiceName())
                .success(trace.isSuccess())
                .input(Map.of())
                .output(null)
                .errorMessage(trace.getErrorMessage())
                .durationMs(trace.getDurationMs())
                .startedAt(trace.getStartedAt())
                .finishedAt(trace.getFinishedAt())
                .runtimeMetadata(Map.of())
                .build())
            .toList();
    }
}
