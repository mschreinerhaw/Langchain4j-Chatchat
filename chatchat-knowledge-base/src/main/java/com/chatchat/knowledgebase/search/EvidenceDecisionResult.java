package com.chatchat.knowledgebase.search;

import java.util.List;

public record EvidenceDecisionResult(
    String contractVersion,
    EvidenceDecisionAction action,
    boolean canAnswer,
    boolean requiresExpansion,
    boolean requiresClarification,
    boolean requiresReview,
    double confidence,
    List<String> reasons,
    EvidenceDecisionPolicy policy,
    EvidenceDecisionTrace trace
) {
    public static final String CONTRACT_VERSION = "evidence_decision_v1";

    public EvidenceDecisionResult {
        contractVersion = contractVersion == null || contractVersion.isBlank()
            ? CONTRACT_VERSION
            : contractVersion;
        action = action == null ? EvidenceDecisionAction.REVIEW_REQUIRED : action;
        confidence = clamp(confidence);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        policy = policy == null ? EvidenceDecisionPolicy.defaults() : policy;
        trace = trace == null ? EvidenceDecisionTrace.empty() : trace;
    }

    public EvidenceDecisionResult(String contractVersion,
                                  EvidenceDecisionAction action,
                                  boolean canAnswer,
                                  boolean requiresExpansion,
                                  boolean requiresClarification,
                                  boolean requiresReview,
                                  double confidence,
                                  List<String> reasons,
                                  EvidenceDecisionPolicy policy) {
        this(
            contractVersion,
            action,
            canAnswer,
            requiresExpansion,
            requiresClarification,
            requiresReview,
            confidence,
            reasons,
            policy,
            null
        );
    }

    public static EvidenceDecisionResult refuse(String reason) {
        return new EvidenceDecisionResult(
            CONTRACT_VERSION,
            EvidenceDecisionAction.REFUSE,
            false,
            false,
            false,
            false,
            0.0D,
            List.of(reason == null || reason.isBlank() ? "No evidence available" : reason),
            EvidenceDecisionPolicy.defaults(),
            EvidenceDecisionTrace.empty()
        );
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
