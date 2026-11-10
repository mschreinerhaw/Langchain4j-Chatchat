package com.chatchat.common.runtime.coding;

import java.util.Map;

/** Public read-only model-call metadata; contains no prompt, response text or tool arguments. */
public record CodingModelCallTelemetry(String operationId, String attemptId, long modelCall, String state,
        String selectedModel, String providerModel, String protocol, String startedAt, String finishedAt,
        Long durationMs, Map<String,Long> usage, String source, String error, Boolean providerDispatched) {
    public CodingModelCallTelemetry(String operationId,String attemptId,int modelCall,String state,
            String selectedModel,String providerModel,String protocol,String startedAt,String finishedAt,
            Long durationMs,Map<String,Long> usage,String source) {
        this(operationId,attemptId,(long)modelCall,state,selectedModel,providerModel,protocol,startedAt,finishedAt,durationMs,usage,source,null,null);
    }
    public CodingModelCallTelemetry {
        if(operationId==null || operationId.length()>128 || !operationId.matches("model-[1-9][0-9]*")
                || modelCall!=Long.parseLong(operationId.substring(6)) || modelCall>CodingProtocol.MAX_SAFE_INTEGER
                || !java.util.Set.of("SUCCEEDED","FAILED","REJECTED_LOCALLY").contains(state)
                || !"JAVA_MODEL_RECEIPT".equals(source) || durationMs!=null && durationMs<0)
            throw new IllegalArgumentException("Invalid model telemetry identity");
        usage=Map.copyOf(usage);
        if(!java.util.Set.of("inputTokens","outputTokens","cacheReadTokens","cacheWriteTokens","reasoningTokens").containsAll(usage.keySet())
                || usage.values().stream().anyMatch(value->value<0))
            throw new IllegalArgumentException("Invalid model telemetry usage");
    }
}
