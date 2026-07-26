package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.evidence.DiagnosticEvidenceQuality;

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
    Map<String, Object> error,
    ToolEvidencePolicy evidencePolicy,
    DiagnosticEvidenceQuality evidenceQuality
) {
    public ToolCallResult(
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
        this(
            diagnosticRunId, batchId, callId, checkId, toolName, normalizedToolName,
            templateId, templateCode, assetId, assetDisplayName, assetToolName, sequence,
            evidenceUsable, status, invoked, durationMs, evidenceId, output, error,
            ToolEvidencePolicy.empty(), null
        );
    }

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
            durationMs, evidenceId, output, error, ToolEvidencePolicy.empty(), null
        );
    }

    public ToolCallResult(
        String callId,
        String toolName,
        String templateCode,
        String assetId,
        String status,
        long durationMs,
        String evidenceId,
        Object output,
        Map<String, Object> error,
        ToolEvidencePolicy evidencePolicy
    ) {
        this(
            null, null, callId, callId, toolName, toolName, templateCode, templateCode,
            assetId, null, null, 0, "SUCCESS".equalsIgnoreCase(status), status, "SUCCESS".equalsIgnoreCase(status),
            durationMs, evidenceId, output, error, evidencePolicy, null
        );
    }
}
