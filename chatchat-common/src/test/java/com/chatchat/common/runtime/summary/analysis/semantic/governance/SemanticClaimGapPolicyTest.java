package com.chatchat.common.runtime.summary.analysis.semantic.governance;

import com.chatchat.common.runtime.summary.analysis.semantic.adapter.SemanticGapAnalysisLoopAdapter;
import com.chatchat.common.runtime.summary.analysis.semantic.model.CapabilityEvidenceClaimContract;
import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticEvidenceGapContract;
import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticOperation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticClaimGapPolicyTest {

    private final SemanticClaimGapPolicy policy = new SemanticClaimGapPolicy();

    @Test
    void turnsEvidenceTimeMismatchIntoRetrievalRequirement() {
        var capability = capability(Set.of(SemanticOperation.OBSERVE, SemanticOperation.TREND));
        var evidence = new CapabilityEvidenceClaimContract.Evidence(
            "evidence-1", "activity-series", Set.of("series.records[1]"), Set.of("10"),
            "DAY", "2026-08-30", "returned entities", false);
        var claim = claim(SemanticOperation.TREND, "DAY", "2026-08-01/2026-08-30");
        var admission = new CapabilityEvidenceClaimContract.Admission(
            false, List.of("EVIDENCE_TIME_SCOPE_MISMATCH"));

        SemanticEvidenceGapContract.Gap gap = policy.derive(capability, evidence, claim, admission);

        assertThat(gap.route()).isEqualTo(SemanticEvidenceGapContract.Route.RETRIEVE_MORE);
        assertThat(gap.requiredOperation()).isEqualTo(SemanticOperation.TREND);
        assertThat(gap.requiredGrain()).isEqualTo("DAY");
        assertThat(gap.requiredTimeScope()).isEqualTo("2026-08-01/2026-08-30");
        assertThat(gap.toMap()).containsEntry("schemaVersion", "semantic_evidence_gap.v1");
    }

    @Test
    void turnsUnauthorizedOperationIntoReplanForCompatibleCapability() {
        var capability = capability(Set.of(SemanticOperation.OBSERVE));
        var claim = claim(SemanticOperation.TREND, "DAY", "returned window");

        SemanticEvidenceGapContract.Gap gap = policy.derive(capability, null, claim,
            new CapabilityEvidenceClaimContract.Admission(false, List.of("OPERATION_NOT_AUTHORIZED")));

        assertThat(gap.route()).isEqualTo(SemanticEvidenceGapContract.Route.REPLAN);
        assertThat(gap.requiredCapabilityId()).isEqualTo("activity-series");
    }

    @Test
    void adaptsSemanticGapToExistingEvidenceCoverageRequest() {
        var gap = policy.derive(capability(Set.of(SemanticOperation.OBSERVE, SemanticOperation.TREND)),
            null, claim(SemanticOperation.TREND, "DAY", "2026-08-01/2026-08-30"),
            new CapabilityEvidenceClaimContract.Admission(false,
                List.of("EVIDENCE_TIME_SCOPE_MISMATCH")));

        var request = new SemanticGapAnalysisLoopAdapter().toGapRequest(gap);

        assertThat(request.requiredCapabilities()).contains("activity-series", "TREND");
        assertThat(request.grain()).isEqualTo("DAY");
        assertThat(request.timeHorizon()).isEqualTo("2026-08-01/2026-08-30");
        assertThat(request.reason()).contains("EVIDENCE_TIME_SCOPE_MISMATCH");
    }

    private CapabilityEvidenceClaimContract.Capability capability(Set<SemanticOperation> operations) {
        return new CapabilityEvidenceClaimContract.Capability(
            "activity-series", "PRODUCER_DECLARED", operations, Set.of("ordered observations"),
            Set.of("value"), Set.of("count"), Set.of("DAY"), Set.of("returned window"),
            Set.of("returned entities"));
    }

    private CapabilityEvidenceClaimContract.Claim claim(SemanticOperation operation,
                                                         String grain,
                                                         String timeScope) {
        return new CapabilityEvidenceClaimContract.Claim(
            "AUTHORIZED_DERIVED_MEASURE", operation, Set.of("series.records[1]"), Set.of("10"),
            Set.of("ordered observations"), Set.of("value"), "count", grain, timeScope,
            "returned entities", "ordered comparison", List.of(), List.of());
    }
}
