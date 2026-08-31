package com.chatchat.agents.assessment;

/**
 * Deterministic Agent Loop policy. A material, actionable evidence gap is closed
 * before synthesis even when the current round already returned partial evidence.
 * Partial evidence is synthesized with limitations only after exploration is no
 * longer available. Evidence gaps are not an answer gate once the bounded loop ends.
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
        if (resolved.materialGap() && resolved.explorationAvailable()) {
            return outcome(Decision.RETRIEVE_MORE, true, true,
                "A material evidence gap remains and an actionable bounded retrieval path is available.");
        }
        if (resolved.evidenceAvailable()) {
            return outcome(Decision.ANALYZE_WITH_LIMITATIONS, true, false,
                "Usable evidence exists, but the remaining gap cannot be closed within the current retrieval boundary.");
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
