package com.chatchat.common.runtime.summary.analysis.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticClaimLifecycleContractTest {

    @Test
    void reEvaluationAppendsRevisionAndPreservesParent() {
        SemanticClaimLifecycleContract.Revision rejected = SemanticClaimLifecycleContract.evolve(
            "claim-fingerprint", "evidence-v1", false, List.of("TIME_SCOPE_MISMATCH"), "gap-1", null);
        SemanticClaimLifecycleContract.Revision admitted = SemanticClaimLifecycleContract.evolve(
            "claim-fingerprint", "evidence-v2", true, List.of(), "", rejected);

        assertThat(rejected.state()).isEqualTo(SemanticClaimLifecycleContract.State.GAP_CREATED);
        assertThat(admitted.revision()).isEqualTo(2);
        assertThat(admitted.parentClaimId()).isEqualTo(rejected.claimId());
        assertThat(admitted.transitions()).containsExactly(
            SemanticClaimLifecycleContract.State.RE_EVALUATED,
            SemanticClaimLifecycleContract.State.VALIDATING,
            SemanticClaimLifecycleContract.State.ADMITTED);
    }
}
