package com.chatchat.common.runtime.summary.analysis.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticClaimAdmissionPolicyTest {

    private final SemanticClaimAdmissionPolicy policy = new SemanticClaimAdmissionPolicy();

    @Test
    void admitsObservedFactFromBoundReturnedEvidenceWithoutInventingSemantics() {
        var capability = capability(Set.of(SemanticOperation.OBSERVE));
        var evidence = evidence("cap-1");
        var claim = claim("OBSERVED_RETURNED_FACT", SemanticOperation.OBSERVE,
            Set.of(), Set.of(), "", "", "", "");

        assertThat(policy.evaluate(capability, evidence, claim).admitted()).isTrue();
    }

    @Test
    void admitsDerivedMeasureOnlyWhenOperationBasisInputsAndScopeAreDeclared() {
        var capability = capability(Set.of(SemanticOperation.OBSERVE, SemanticOperation.DERIVE));
        var evidence = evidence("cap-1");
        var claim = claim("AUTHORIZED_DERIVED_MEASURE", SemanticOperation.DERIVE,
            Set.of("ratio = numerator / denominator"), Set.of("NUMERATOR", "DENOMINATOR"),
            "percent", "account", "2026-08-31", "returned accounts");

        assertThat(policy.evaluate(capability, evidence, claim).admitted()).isTrue();
    }

    @Test
    void rejectsSemanticallySimilarTextAndCapabilityMismatch() {
        var capability = capability(Set.of(SemanticOperation.OBSERVE, SemanticOperation.DERIVE));
        var evidence = evidence("different-capability");
        var claim = claim("AUTHORIZED_DERIVED_MEASURE", SemanticOperation.DERIVE,
            Set.of("ratio = numerator / denominator for any population"),
            Set.of("NUMERATOR", "DENOMINATOR"), "percent", "account",
            "2026-08-31", "returned accounts");

        assertThat(policy.evaluate(capability, evidence, claim).rejectionCodes())
            .contains("EVIDENCE_NOT_BOUND_TO_CAPABILITY", "SEMANTIC_BASIS_MISMATCH");
    }

    @Test
    void rejectsProxyInferenceWhenOnlyDerivationWasAuthorized() {
        var capability = capability(Set.of(SemanticOperation.OBSERVE, SemanticOperation.DERIVE));
        var evidence = evidence("cap-1");
        var claim = new CapabilityEvidenceClaimContract.Claim(
            "CALIBRATED_INFERENCE", SemanticOperation.PROXY, Set.of("dataset.records[1]"),
            Set.of("10", "20"), Set.of("ratio = numerator / denominator"), Set.of(),
            "", "account", "2026-08-31", "returned accounts", "proxy reasoning",
            List.of("single period"), List.of("price effect"));

        assertThat(policy.evaluate(capability, evidence, claim).rejectionCodes())
            .contains("OPERATION_NOT_AUTHORIZED");
    }

    private CapabilityEvidenceClaimContract.Capability capability(Set<SemanticOperation> operations) {
        return new CapabilityEvidenceClaimContract.Capability(
            "cap-1", "PRODUCER_DECLARED", operations,
            Set.of("ratio = numerator / denominator"), Set.of("NUMERATOR", "DENOMINATOR"),
            Set.of("percent"), Set.of("account"), Set.of("2026-08-31"),
            Set.of("returned accounts"));
    }

    private CapabilityEvidenceClaimContract.Evidence evidence(String capabilityId) {
        return new CapabilityEvidenceClaimContract.Evidence(
            "evidence-1", capabilityId, Set.of("dataset.records[1]"), Set.of("10", "20"),
            "account", "2026-08-31", "returned accounts", true);
    }

    private CapabilityEvidenceClaimContract.Claim claim(String claimClass,
                                                         SemanticOperation operation,
                                                         Set<String> basis,
                                                         Set<String> inputs,
                                                         String unit,
                                                         String grain,
                                                         String timeScope,
                                                         String populationScope) {
        return new CapabilityEvidenceClaimContract.Claim(
            claimClass, operation, Set.of("dataset.records[1]"), Set.of("10", "20"), basis,
            inputs, unit, grain, timeScope, populationScope,
            claimClass.equals("AUTHORIZED_DERIVED_MEASURE") ? "10 / 20" : "",
            List.of(), List.of());
    }
}
