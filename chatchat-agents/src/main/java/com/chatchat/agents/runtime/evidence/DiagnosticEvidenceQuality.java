package com.chatchat.agents.runtime.evidence;

import com.chatchat.agents.runtime.batch.ToolEvidencePolicy;

import java.util.List;

/**
 * Quality is deliberately independent from execution coverage.
 */
public record DiagnosticEvidenceQuality(
    String contractVersion,
    double coverage,
    Dimension quality,
    Dimension freshness,
    String assessmentCapability,
    List<String> missingMetrics,
    List<String> missingContext,
    String purpose,
    Boolean healthCapability,
    String timeSemantics,
    List<String> reasons
) {
    public static final String CONTRACT_VERSION = "diagnostic_evidence_quality_v1";

    public DiagnosticEvidenceQuality {
        contractVersion = CONTRACT_VERSION;
        missingMetrics = missingMetrics == null ? List.of() : List.copyOf(missingMetrics);
        missingContext = missingContext == null ? List.of() : List.copyOf(missingContext);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static DiagnosticEvidenceQuality unavailable(ToolEvidencePolicy policy, String reason) {
        ToolEvidencePolicy effective = policy == null ? ToolEvidencePolicy.empty() : policy;
        return new DiagnosticEvidenceQuality(
            CONTRACT_VERSION,
            0.0D,
            Dimension.unknown("No successful diagnostic result is available."),
            Dimension.unknown("Freshness cannot be assessed without successful evidence."),
            "UNAVAILABLE",
            effective.requiredMetrics(),
            effective.requiresContext(),
            effective.purpose(),
            effective.healthCapability(),
            effective.timeSemantics(),
            reason == null ? List.of() : List.of(reason)
        );
    }

    public record Dimension(
        Double value,
        String status,
        String type,
        String reason
    ) {
        public static Dimension assessed(double value, String reason) {
            return new Dimension(value, value >= 1.0D ? "COMPLETE" : "INCOMPLETE", "RUNTIME_COMPUTED", reason);
        }

        public static Dimension unknown(String reason) {
            return new Dimension(null, "UNKNOWN", "NOT_ASSESSED", reason);
        }
    }
}
