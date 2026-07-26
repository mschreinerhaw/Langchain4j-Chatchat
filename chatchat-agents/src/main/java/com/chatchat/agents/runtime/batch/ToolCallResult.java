package com.chatchat.agents.runtime.batch;

import java.util.Map;

public record ToolCallResult(
    String diagnosticRunId,
    String batchId,
    String callId,
    String checkId,
    String toolName,
    String normalizedToolName,
    String templateId,
    String templateCode,
    String assetId,
    String assetDisplayName,
    String assetToolName,
    int sequence,
    boolean evidenceUsable,
    String status,
    boolean invoked,
    long durationMs,
    String evidenceId,
    Object output,
    Map<String, Object> error
) {
    public ToolCallResult(
        String callId,
        String toolName,
        String templateCode,
        String assetId,
        String status,
        long durationMs,
        String evidenceId,
        Object output,
        Map<String, Object> error
    ) {
        this(
            null, null, callId, callId, toolName, toolName, templateCode, templateCode,
            assetId, null, null, 0, "SUCCESS".equalsIgnoreCase(status), status, "SUCCESS".equalsIgnoreCase(status),
            durationMs, evidenceId, output, error
        );
    }
}
