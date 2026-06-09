package com.chatchat.common.interaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool execution trace for auditability and debugging.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractionToolTrace {
    private String toolName;
    private String displayName;
    private String serviceId;
    private String serviceName;
    private boolean success;
    private Map<String, Object> input;
    private String output;
    private String errorMessage;
    private Long durationMs;
    private Long startedAt;
    private Long finishedAt;

    @Builder.Default
    private Map<String, Object> runtimeMetadata = new HashMap<>();
}
