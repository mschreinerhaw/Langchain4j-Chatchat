package com.chatchat.common.runtime.coding;

import java.util.Map;

/** Public read-only model-call metadata; contains no prompt, response text or tool arguments. */
public record CodingModelCallTelemetry(String operationId, String attemptId, int modelCall, String state,
        String selectedModel, String providerModel, String protocol, String startedAt, String finishedAt,
        Long durationMs, Map<String,Long> usage, String source) {
    public CodingModelCallTelemetry {
        if(operationId==null || !operationId.matches("model-[1-9][0-9]{0,5}")
                || modelCall!=Integer.parseInt(operationId.substring(6)) || !"SUCCEEDED".equals(state)
                || !"JAVA_MODEL_RECEIPT".equals(source) || durationMs!=null && durationMs<0)
            throw new IllegalArgumentException("Invalid model telemetry identity");
        usage=Map.copyOf(usage);
        if(!java.util.Set.of("inputTokens","outputTokens").containsAll(usage.keySet())
                || usage.values().stream().anyMatch(value->value<0))
            throw new IllegalArgumentException("Invalid model telemetry usage");
    }
}
