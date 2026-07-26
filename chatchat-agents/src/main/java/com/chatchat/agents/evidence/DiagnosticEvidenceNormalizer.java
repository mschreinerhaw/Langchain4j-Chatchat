package com.chatchat.agents.evidence;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts runtime batch transport records into a stable diagnostic evidence object.
 */
public final class DiagnosticEvidenceNormalizer {

    public static final String CONTRACT_VERSION = "diagnostic_evidence_v1";

    public Object normalize(Object output) {
        if (!(output instanceof ToolCallBatchResult batch)) {
            return output;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int usable = 0;
        for (ToolCallResult child : batch.results()) {
            if (child == null) {
                continue;
            }
            if (child.evidenceUsable()) {
                usable++;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            put(evidence, "diagnosticRunId", child.diagnosticRunId());
            put(evidence, "batchId", child.batchId());
            put(evidence, "callId", child.callId());
            put(evidence, "checkId", child.checkId());
            put(evidence, "toolName", child.toolName());
            put(evidence, "normalizedToolName", child.normalizedToolName());
            put(evidence, "templateId", child.templateId());
            put(evidence, "templateCode", child.templateCode());
            put(evidence, "assetId", child.assetId());
            put(evidence, "assetDisplayName", child.assetDisplayName());
            put(evidence, "assetToolName", child.assetToolName());
            evidence.put("sequence", child.sequence());
            evidence.put("status", child.status());
            evidence.put("invoked", child.invoked());
            evidence.put("evidenceUsable", child.evidenceUsable());
            evidence.put("durationMs", child.durationMs());
            put(evidence, "evidenceId", child.evidenceId());
            put(evidence, "finding", child.output());
            if (child.error() != null && !child.error().isEmpty()) {
                evidence.put("error", child.error());
            }
            results.add(Map.copyOf(evidence));
        }

        int declared = batch.cardinality() == null
            ? batch.summary() == null ? results.size() : batch.summary().total()
            : batch.cardinality().declaredCheckCount();
        double coverage = declared <= 0 ? 0.0D : round((double) usable / declared);
        DiagnosticRunStateMachine.Outcome executionStatus = usable == declared && declared > 0
            ? DiagnosticRunStateMachine.Outcome.SUCCESS
            : usable > 0
                ? DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS
                : DiagnosticRunStateMachine.Outcome.INSUFFICIENT_EVIDENCE;
        DiagnosticRunStateMachine.AssessmentStatus assessmentStatus = usable > 0
            ? DiagnosticRunStateMachine.AssessmentStatus.PRELIMINARY_AVAILABLE
            : DiagnosticRunStateMachine.AssessmentStatus.NOT_AVAILABLE;

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("contractVersion", CONTRACT_VERSION);
        put(normalized, "batchId", batch.batchId());
        put(normalized, "executionMode", batch.executionMode());
        put(normalized, "startedAt", batch.startedAt());
        put(normalized, "completedAt", batch.completedAt());
        normalized.put("batchStatus", batch.status());
        normalized.put("executionStatus", executionStatus.wireValue());
        normalized.put("assessmentStatus", assessmentStatus.wireValue());
        normalized.put("evidenceCoverage", coverage);
        normalized.put("cardinality", batch.cardinality());
        normalized.put("summary", batch.summary());
        normalized.put("results", List.copyOf(results));
        return Map.copyOf(normalized);
    }

    private void put(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }
}
