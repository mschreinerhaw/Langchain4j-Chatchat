package com.chatchat.common.runtime.analysis.execution;

import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import java.util.List;

/** Pure flow policy. Analysis owns judgments; governed providers own tool execution. */
public final class AdaptiveAnalysisController {
    public static final int MAX_ANALYSIS_ROUNDS = 3;

    public static int boundedRounds(int requested) {
        return Math.max(0, Math.min(MAX_ANALYSIS_ROUNDS, requested));
    }

    public static int boundedRecoveryRounds(int requested) {
        return Math.max(0, Math.min(MAX_ANALYSIS_ROUNDS - 1, requested));
    }

    public record Feedback(List<EvidenceGap> gaps, boolean verified, boolean progress,
                           boolean providerFailed, boolean providerExhausted) {
        public Feedback { gaps = gaps == null ? List.of() : List.copyOf(gaps); }
    }

    public enum Action { RECOVER, DELIVER, STOP }
    public record Decision(Action action, String reason) {}

    public record ReplanFeedback(boolean approvalRequired, int completedRewrites, int attemptLimit,
                                 boolean dependencyRecovery, boolean invalidPlan,
                                 boolean discoveryContinuation, boolean untriedAuthorizedTool) {}
    public record ReplanDecision(boolean allowed, boolean structuralRepair, String reason) {}

    /** Uses Runtime admission facts; it neither discovers tools nor grants authorization. */
    public ReplanDecision decideReplan(ReplanFeedback feedback) {
        if (feedback.approvalRequired()) return new ReplanDecision(false, false, "authorization_required");
        int attempts = Math.max(1, boundedRounds(feedback.attemptLimit()));
        if (feedback.completedRewrites() >= attempts - 1)
            return new ReplanDecision(false, false, "runtime_attempt_limit");
        if (feedback.dependencyRecovery()) return new ReplanDecision(true, false, "dependency_replan_required");
        if (feedback.invalidPlan()) return new ReplanDecision(feedback.completedRewrites() == 0, true,
            feedback.completedRewrites() == 0 ? "invalid_plan" : "structural_repair_already_attempted");
        if (feedback.discoveryContinuation()) return new ReplanDecision(true, false, "template_discovery_next_page");
        return new ReplanDecision(feedback.untriedAuthorizedTool(), false,
            feedback.untriedAuthorizedTool() ? "untried_evidence_tool" : "no_verified_new_retrieval_path");
    }

    public Decision decideModel(java.util.Map<String,Object> metadata, int completedRecoveryRounds, int recoveryBudget) {
        var action = ModelAnalysisIntent.action(metadata);
        if ("RESOURCE_BUDGET_EXHAUSTED".equals(metadata.get("executionStopReason")))
            return new Decision(Action.STOP, "BUDGET_EXHAUSTED");
        if (!ModelAnalysisIntent.continuing(metadata))
            return new Decision(ModelAnalysisIntent.publishRequested(metadata) ? Action.DELIVER : Action.STOP, "MODEL_" + action);
        if (completedRecoveryRounds >= boundedRecoveryRounds(recoveryBudget))
            return new Decision(Action.STOP, "BUDGET_EXHAUSTED");
        return new Decision(Action.RECOVER, "MODEL_CONTINUE");
    }
    public ReplanDecision admitModelReplan(boolean authorizationRequired, int completedRewrites, int attemptLimit) {
        if (authorizationRequired) return new ReplanDecision(false, false, "authorization_required");
        if (completedRewrites >= Math.max(1, boundedRounds(attemptLimit)) - 1)
            return new ReplanDecision(false, false, "runtime_attempt_limit");
        return new ReplanDecision(true, false, "model_requested_replan");
    }
    public Decision decide(Feedback feedback, int completedRecoveryRounds, int recoveryBudget) {
        if (feedback.providerFailed()) return new Decision(Action.STOP, "FAILED");
        if (feedback.gaps().isEmpty()) return feedback.verified()
            ? new Decision(Action.DELIVER, "VERIFIED")
            : new Decision(Action.STOP, "VERIFICATION_REQUIRED");
        if (feedback.providerExhausted()) return new Decision(Action.STOP, "EXHAUSTED");
        if (!feedback.progress()) return new Decision(Action.STOP, "NO_NEW_EVIDENCE");
        int budget = boundedRecoveryRounds(recoveryBudget);
        if (completedRecoveryRounds >= budget) return new Decision(Action.STOP,
            budget == 0 ? "DISABLED" : "BUDGET_EXHAUSTED");
        return new Decision(Action.RECOVER, "EVIDENCE_GAPS");
    }
}
