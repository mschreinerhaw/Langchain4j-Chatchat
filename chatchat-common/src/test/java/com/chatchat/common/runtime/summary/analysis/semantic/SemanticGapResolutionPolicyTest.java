package com.chatchat.common.runtime.summary.analysis.semantic;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticGapResolutionPolicyTest {

    private final SemanticGapResolutionPolicy policy = new SemanticGapResolutionPolicy();

    @Test
    void unchangedEvidenceTerminatesRepeatedRetrieval() {
        SemanticEvidenceGapContract.Gap gap = gap(SemanticEvidenceGapContract.Route.RETRIEVE_MORE);
        SemanticGapResolutionPolicy.State first = policy.evaluate(gap, "e1", "c1", null, 3);
        SemanticGapResolutionPolicy.State repeated = policy.evaluate(gap, "e1", "c1", first, 3);

        assertThat(repeated.terminal()).isTrue();
        assertThat(repeated.terminalReason())
            .isEqualTo(SemanticGapResolutionPolicy.TerminalReason.NO_NEW_EVIDENCE);
        assertThat(repeated.lastResolution())
            .isEqualTo(SemanticEvidenceGapContract.Route.ANALYZE_WITH_LIMITATIONS);
    }

    @Test
    void unchangedCapabilityTerminatesRepeatedReplan() {
        SemanticEvidenceGapContract.Gap gap = gap(SemanticEvidenceGapContract.Route.REPLAN);
        SemanticGapResolutionPolicy.State first = policy.evaluate(gap, "e1", "c1", null, 3);
        SemanticGapResolutionPolicy.State repeated = policy.evaluate(gap, "e2", "c1", first, 3);

        assertThat(repeated.terminalReason())
            .isEqualTo(SemanticGapResolutionPolicy.TerminalReason.CAPABILITY_UNCHANGED);
    }

    @Test
    void newEvidenceAllowsAnotherBoundedAttempt() {
        SemanticEvidenceGapContract.Gap gap = gap(SemanticEvidenceGapContract.Route.RETRIEVE_MORE);
        SemanticGapResolutionPolicy.State first = policy.evaluate(gap, "e1", "c1", null, 2);
        SemanticGapResolutionPolicy.State progressed = policy.evaluate(gap, "e2", "c1", first, 2);

        assertThat(progressed.terminal()).isFalse();
        assertThat(progressed.attemptCount()).isEqualTo(2);
    }

    private SemanticEvidenceGapContract.Gap gap(SemanticEvidenceGapContract.Route route) {
        return new SemanticEvidenceGapContract.Gap("gap-1", route, Set.of("MISSING"),
            "capability", SemanticOperation.TREND, Set.of(), "", "DAY", "", "", Set.of());
    }
}
