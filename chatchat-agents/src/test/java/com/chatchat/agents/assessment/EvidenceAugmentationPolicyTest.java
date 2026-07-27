package com.chatchat.agents.assessment;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAugmentationPolicyTest {

    private final EvidenceAugmentationPolicy policy = new EvidenceAugmentationPolicy();

    @Test
    void contractIdentityAndDecisionNamesAreFrozenForV1() {
        assertThat(EvidenceAugmentationPolicy.CONTRACT_VERSION)
            .isEqualTo("evidence_augmentation_decision_v1");
        assertThat(Arrays.stream(EvidenceAugmentationPolicy.Decision.values())
            .map(Enum::name)
            .toList())
            .containsExactly(
                "COMPLETE",
                "RETRIEVE_MORE",
                "ANALYZE_WITH_LIMITATIONS",
                "NO_EVIDENCE",
                "EXACT_RESULT_UNAVAILABLE",
                "BLOCKED_AUTHORIZATION"
            );
    }

    @Test
    void retrievesMoreWhenAnActionableGapHasBudget() {
        EvidenceAugmentationPolicy.Outcome outcome = policy.decide(new EvidenceAugmentationPolicy.Context(
            true, false, true, true, false, TaskContract.EvidenceRequirement.REQUIRED));

        assertThat(outcome.decision()).isEqualTo(EvidenceAugmentationPolicy.Decision.RETRIEVE_MORE);
        assertThat(outcome.continueLoop()).isTrue();
        assertThat(outcome.answerAllowed()).isTrue();
    }

    @Test
    void partialEvidenceAlwaysAllowsAnalysisWhenExplorationStops() {
        EvidenceAugmentationPolicy.Outcome outcome = policy.decide(new EvidenceAugmentationPolicy.Context(
            true, false, true, false, false, TaskContract.EvidenceRequirement.STRICT));

        assertThat(outcome.decision())
            .isEqualTo(EvidenceAugmentationPolicy.Decision.ANALYZE_WITH_LIMITATIONS);
        assertThat(outcome.answerAllowed()).isTrue();
        assertThat(outcome.continueLoop()).isFalse();
    }

    @Test
    void onlyMissingStrictEvidenceCanBlockAnExactResult() {
        EvidenceAugmentationPolicy.Outcome outcome = policy.decide(new EvidenceAugmentationPolicy.Context(
            false, false, true, false, false, TaskContract.EvidenceRequirement.STRICT));

        assertThat(outcome.decision())
            .isEqualTo(EvidenceAugmentationPolicy.Decision.EXACT_RESULT_UNAVAILABLE);
        assertThat(outcome.answerAllowed()).isFalse();
    }

    @Test
    void optionalKnowledgeTaskCanAnswerWithoutToolEvidence() {
        EvidenceAugmentationPolicy.Outcome outcome = policy.decide(new EvidenceAugmentationPolicy.Context(
            false, false, false, false, false, TaskContract.EvidenceRequirement.OPTIONAL));

        assertThat(outcome.decision())
            .isEqualTo(EvidenceAugmentationPolicy.Decision.ANALYZE_WITH_LIMITATIONS);
        assertThat(outcome.answerAllowed()).isTrue();
    }
}
