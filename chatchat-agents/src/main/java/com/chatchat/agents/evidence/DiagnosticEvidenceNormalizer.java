package com.chatchat.agents.evidence;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.batch.ToolEvidencePolicy;
import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        Set<String> batchFields = new HashSet<>();
        for (ToolCallResult child : batch.results()) {
            if (child != null && child.evidenceUsable()) {
                collectFields(child.output(), batchFields);
            }
        }
        int usable = 0;
        int qualityAssessed = 0;
        double qualityTotal = 0.0D;
        int freshnessAssessed = 0;
        double freshnessTotal = 0.0D;
        boolean allAssessmentCapabilitiesFull = true;
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
            DiagnosticEvidenceQuality quality = assessQuality(child, batchFields);
            evidence.put("evidenceQuality", quality);
            if (quality.quality().value() != null) {
                qualityAssessed++;
                qualityTotal += quality.quality().value();
            }
            if (quality.freshness().value() != null) {
                freshnessAssessed++;
                freshnessTotal += quality.freshness().value();
            }
            if (!"FULL".equals(quality.assessmentCapability())) {
                allAssessmentCapabilitiesFull = false;
            }
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
        DiagnosticRunStateMachine.AssessmentStatus assessmentStatus = usable <= 0
            ? DiagnosticRunStateMachine.AssessmentStatus.NOT_AVAILABLE
            : coverage >= 1.0D && allAssessmentCapabilitiesFull
                ? DiagnosticRunStateMachine.AssessmentStatus.COMPLETE
                : DiagnosticRunStateMachine.AssessmentStatus.PRELIMINARY_AVAILABLE;

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
        normalized.put("evidenceQuality", aggregateQuality(
            declared,
            qualityAssessed,
            qualityTotal,
            freshnessAssessed,
            freshnessTotal
        ));
        normalized.put("cardinality", batch.cardinality());
        normalized.put("summary", batch.summary());
        normalized.put("results", List.copyOf(results));
        return Map.copyOf(normalized);
    }

    private DiagnosticEvidenceQuality assessQuality(ToolCallResult child, Set<String> batchFields) {
        ToolEvidencePolicy policy = child.evidencePolicy() == null
            ? ToolEvidencePolicy.empty()
            : child.evidencePolicy();
        if (!child.evidenceUsable()) {
            return DiagnosticEvidenceQuality.unavailable(policy, "Execution produced no accepted evidence.");
        }

        Set<String> resultFields = new HashSet<>();
        collectFields(child.output(), resultFields);
        List<String> missingMetrics = missing(policy.requiredMetrics(), resultFields);
        List<String> missingContext = missing(policy.requiresContext(), batchFields);
        DiagnosticEvidenceQuality.Dimension quality = policy.requiredMetrics().isEmpty()
            ? DiagnosticEvidenceQuality.Dimension.unknown(
                "The template did not declare requiredMetrics; metric completeness was not assessed.")
            : DiagnosticEvidenceQuality.Dimension.assessed(
                round((double) (policy.requiredMetrics().size() - missingMetrics.size())
                    / policy.requiredMetrics().size()),
                missingMetrics.isEmpty()
                    ? "All template-declared required metrics are present."
                    : "Missing template-declared metrics: " + String.join(", ", missingMetrics));
        DiagnosticEvidenceQuality.Dimension freshness = policy.freshnessMaxAgeSeconds() == null
            ? DiagnosticEvidenceQuality.Dimension.unknown(
                "The template did not declare a freshness policy.")
            : DiagnosticEvidenceQuality.Dimension.assessed(
                1.0D,
                "Evidence was observed in the current batch execution.");

        List<String> reasons = new ArrayList<>();
        if (Boolean.FALSE.equals(policy.healthCapability())) {
            reasons.add("Template purpose does not authorize a direct health conclusion.");
        }
        if (!missingMetrics.isEmpty()) {
            reasons.add("Required health metrics are incomplete.");
        }
        if (!missingContext.isEmpty()) {
            reasons.add("Time interpretation context is missing: " + String.join(", ", missingContext));
        }
        String assessmentCapability;
        if (Boolean.TRUE.equals(policy.healthCapability())
            && !policy.requiredMetrics().isEmpty()
            && missingMetrics.isEmpty()
            && missingContext.isEmpty()) {
            assessmentCapability = "FULL";
        } else if (Boolean.FALSE.equals(policy.healthCapability())
            || !missingMetrics.isEmpty()
            || !missingContext.isEmpty()) {
            assessmentCapability = "LIMITED";
        } else {
            assessmentCapability = "PRELIMINARY";
        }
        return new DiagnosticEvidenceQuality(
            DiagnosticEvidenceQuality.CONTRACT_VERSION,
            1.0D,
            quality,
            freshness,
            assessmentCapability,
            missingMetrics,
            missingContext,
            policy.purpose(),
            policy.healthCapability(),
            policy.timeSemantics(),
            reasons
        );
    }

    private Map<String, Object> aggregateQuality(int declared,
                                                 int qualityAssessed,
                                                 double qualityTotal,
                                                 int freshnessAssessed,
                                                 double freshnessTotal) {
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("contractVersion", "diagnostic_evidence_quality_summary_v1");
        quality.put("assessedChecks", qualityAssessed);
        quality.put("declaredChecks", declared);
        quality.put("qualityAssessmentCoverage",
            declared <= 0 ? 0.0D : round((double) qualityAssessed / declared));
        quality.put("qualityScore",
            qualityAssessed == 0 ? null : round(qualityTotal / qualityAssessed));
        quality.put("freshnessScore",
            freshnessAssessed == 0 ? null : round(freshnessTotal / freshnessAssessed));
        return quality;
    }

    private List<String> missing(List<String> required, Set<String> available) {
        if (required == null || required.isEmpty()) {
            return List.of();
        }
        return required.stream()
            .filter(metric -> !available.contains(normalizedField(metric)))
            .toList();
    }

    private void collectFields(Object value, Set<String> fields) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    fields.add(normalizedField(String.valueOf(entry.getKey())));
                }
                collectFields(entry.getValue(), fields);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectFields(item, fields));
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectFields(java.lang.reflect.Array.get(value, index), fields);
            }
        }
    }

    private String normalizedField(String value) {
        return value == null
            ? ""
            : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
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
