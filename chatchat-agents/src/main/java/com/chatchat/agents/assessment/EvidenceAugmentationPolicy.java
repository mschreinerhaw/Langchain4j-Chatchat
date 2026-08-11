package com.chatchat.agents.assessment;

/**
 * Deterministic Agent Loop policy. Usable partial evidence terminates exploration
 * with explicit limitations; only the absence of usable evidence justifies another
 * retrieval attempt. Evidence gaps are not an answer gate.
 */
public final class EvidenceAugmentationPolicy {

    public static final String CONTRACT_VERSION = "evidence_augmentation_decision_v1";

    public Outcome decide(Context context) {
        Context resolved = context == null ? Context.empty() : context;
        if (resolved.authorizationRequired()) {
            return outcome(Decision.BLOCKED_AUTHORIZATION, false, false,
                "The requested action requires authorization before the loop can continue.");
        }
        if (resolved.evidenceSufficient()) {
            return outcome(Decision.COMPLETE, true, false,
                "The current evidence supports completion.");
        }
        if (resolved.evidenceAvailable()) {
            return outcome(Decision.ANALYZE_WITH_LIMITATIONS, true, false,
                "Usable evidence exists; remaining gaps affect confidence and limitations, not answer permission.");
        }
        if (resolved.materialGap() && resolved.explorationAvailable()) {
            return outcome(Decision.RETRIEVE_MORE, true, true,
                "No usable evidence is available and an actionable retrieval path remains.");
        }
        if (resolved.evidenceRequirement() == TaskContract.EvidenceRequirement.OPTIONAL) {
            return outcome(Decision.ANALYZE_WITH_LIMITATIONS, true, false,
                "The task permits a best-effort answer without external evidence.");
        }
        if (resolved.evidenceRequirement() == TaskContract.EvidenceRequirement.STRICT) {
            return outcome(Decision.EXACT_RESULT_UNAVAILABLE, false, false,
                "The task requires an exact result, but no usable evidence is available.");
        }
        return outcome(Decision.NO_EVIDENCE, false, false,
            "The evidence-required task has no usable factual result after exploration.");
    }

    private Outcome outcome(Decision decision, boolean answerAllowed, boolean continueLoop, String reason) {
        return new Outcome(CONTRACT_VERSION, decision, answerAllowed, continueLoop, reason);
    }

    public enum Decision {
        COMPLETE,
        RETRIEVE_MORE,
        ANALYZE_WITH_LIMITATIONS,
        NO_EVIDENCE,
        EXACT_RESULT_UNAVAILABLE,
        BLOCKED_AUTHORIZATION
    }

    public record Context(
        boolean evidenceAvailable,
        boolean evidenceSufficient,
        boolean materialGap,
        boolean explorationAvailable,
        boolean authorizationRequired,
        TaskContract.EvidenceRequirement evidenceRequirement
    ) {
        public Context {
            evidenceRequirement = evidenceRequirement == null
                ? TaskContract.EvidenceRequirement.OPTIONAL
                : evidenceRequirement;
        }

        public static Context empty() {
            return new Context(false, false, false, false, false,
                TaskContract.EvidenceRequirement.OPTIONAL);
        }
    }

    public record Outcome(
        String contractVersion,
        Decision decision,
        boolean answerAllowed,
        boolean continueLoop,
        String reason
    ) {
    }
}
