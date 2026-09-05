package com.chatchat.common.runtime.summary.analysis.semantic.governance;

import com.chatchat.common.runtime.summary.analysis.semantic.model.CapabilityEvidenceClaimContract;
import com.chatchat.common.runtime.summary.analysis.semantic.model.SemanticEvidenceGapContract;
import java.util.Set;

/** Converts a rejected claim into one actionable semantic gap without business interpretation. */
public final class SemanticClaimGapPolicy {

    private static final Set<String> CAPABILITY_GAPS = Set.of(
        "CAPABILITY_UNDECLARED", "OPERATION_NOT_AUTHORIZED", "SEMANTIC_BASIS_MISMATCH",
        "FIELD_NOT_AUTHORIZED", "UNIT_MISMATCH", "GRAIN_MISMATCH", "TIME_SCOPE_MISMATCH",
        "POPULATION_SCOPE_MISMATCH");
    private static final Set<String> EVIDENCE_GAPS = Set.of(
        "EVIDENCE_NOT_BOUND_TO_CAPABILITY", "EVIDENCE_REFERENCE_MISSING", "SUPPORTING_VALUE_MISSING",
        "EVIDENCE_GRAIN_MISMATCH", "EVIDENCE_TIME_SCOPE_MISMATCH",
        "EVIDENCE_POPULATION_SCOPE_MISMATCH");

    public SemanticEvidenceGapContract.Gap derive(
        CapabilityEvidenceClaimContract.Capability capability,
        CapabilityEvidenceClaimContract.Evidence evidence,
        CapabilityEvidenceClaimContract.Claim claim,
        CapabilityEvidenceClaimContract.Admission admission) {
        if (admission == null || admission.admitted() || admission.rejectionCodes().isEmpty()) return null;
        Set<String> codes = Set.copyOf(admission.rejectionCodes());
        SemanticEvidenceGapContract.Route route = route(codes);
        String capabilityId = claimCapabilityId(capability, evidence);
        return new SemanticEvidenceGapContract.Gap(
            "", route, codes, capabilityId, claim == null ? null : claim.operation(),
            claim == null ? Set.of() : claim.inputFields(), claim == null ? "" : claim.outputUnit(),
            claim == null ? "" : claim.grain(), claim == null ? "" : claim.timeScope(),
            claim == null ? "" : claim.populationScope(),
            claim == null ? Set.of() : claim.evidenceReferences());
    }

    private SemanticEvidenceGapContract.Route route(Set<String> codes) {
        if (codes.stream().anyMatch(CAPABILITY_GAPS::contains)) {
            return SemanticEvidenceGapContract.Route.REPLAN;
        }
        if (codes.stream().anyMatch(EVIDENCE_GAPS::contains)) {
            return SemanticEvidenceGapContract.Route.RETRIEVE_MORE;
        }
        return SemanticEvidenceGapContract.Route.ANALYZE_WITH_LIMITATIONS;
    }

    private String claimCapabilityId(CapabilityEvidenceClaimContract.Capability capability,
                                     CapabilityEvidenceClaimContract.Evidence evidence) {
        if (capability != null && !capability.capabilityId().isBlank()) return capability.capabilityId();
        return evidence == null ? "" : evidence.capabilityId();
    }
}
