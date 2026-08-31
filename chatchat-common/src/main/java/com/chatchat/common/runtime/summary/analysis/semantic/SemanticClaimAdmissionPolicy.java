package com.chatchat.common.runtime.summary.analysis.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Deterministic fail-closed admission for Capability -> Evidence -> Claim. */
public final class SemanticClaimAdmissionPolicy {

    public CapabilityEvidenceClaimContract.Admission evaluate(
        CapabilityEvidenceClaimContract.Capability capability,
        CapabilityEvidenceClaimContract.Evidence evidence,
        CapabilityEvidenceClaimContract.Claim claim) {
        List<String> rejected = new ArrayList<>();
        if (evidence == null || capability == null
            || evidence.capabilityId().isBlank()
            || !evidence.capabilityId().equals(capability.capabilityId())) {
            rejected.add("EVIDENCE_NOT_BOUND_TO_CAPABILITY");
        }
        if (claim == null) {
            rejected.add("CLAIM_CONTRACT_MISSING");
            return decision(rejected);
        }
        if (evidence == null || claim.evidenceReferences().isEmpty()
            || !evidence.recordReferences().containsAll(claim.evidenceReferences())) {
            rejected.add("EVIDENCE_REFERENCE_MISSING");
        }
        if (evidence == null || claim.supportingValues().isEmpty()
            || !evidence.exactValues().containsAll(claim.supportingValues())) {
            rejected.add("SUPPORTING_VALUE_MISSING");
        }
        String claimClass = claim.claimClass();
        if (!Set.of("OBSERVED_RETURNED_FACT", "AUTHORIZED_DERIVED_MEASURE", "CALIBRATED_INFERENCE")
            .contains(claimClass)) rejected.add("CLAIM_CLASS_UNSUPPORTED");

        if ("OBSERVED_RETURNED_FACT".equals(claimClass)) {
            if (claim.operation() != SemanticOperation.OBSERVE) rejected.add("OPERATION_CLASS_MISMATCH");
            return decision(rejected);
        }

        if (capability == null || !capability.producerDeclared()) rejected.add("CAPABILITY_UNDECLARED");

        if (claim.operation() == null || capability == null
            || !capability.allowedOperations().contains(claim.operation())) {
            rejected.add("OPERATION_NOT_AUTHORIZED");
        }
        if (claim.semanticBasis().isEmpty()) rejected.add("SEMANTIC_BASIS_MISSING");
        else if (capability == null
            || !capability.declaredSemanticBasis().containsAll(claim.semanticBasis())) {
            rejected.add("SEMANTIC_BASIS_MISMATCH");
        }

        if ("AUTHORIZED_DERIVED_MEASURE".equals(claimClass)) {
            if (claim.operation() == SemanticOperation.INFER || claim.operation() == SemanticOperation.PROXY
                || claim.operation() == SemanticOperation.OBSERVE) rejected.add("OPERATION_CLASS_MISMATCH");
            if (claim.method().isBlank()) rejected.add("DERIVATION_METHOD_MISSING");
            if (claim.inputFields().isEmpty()) rejected.add("DERIVATION_INPUTS_MISSING");
            if (claim.outputUnit().isBlank()) rejected.add("OUTPUT_UNIT_MISSING");
            if (claim.grain().isBlank()) rejected.add("GRAIN_MISSING");
            if (claim.timeScope().isBlank()) rejected.add("TIME_SCOPE_MISSING");
            if (claim.populationScope().isBlank()) rejected.add("POPULATION_SCOPE_MISSING");
        }
        if ("CALIBRATED_INFERENCE".equals(claimClass)) {
            if (claim.operation() != SemanticOperation.INFER && claim.operation() != SemanticOperation.PROXY) {
                rejected.add("OPERATION_CLASS_MISMATCH");
            }
            if (claim.caveats().isEmpty()) rejected.add("INFERENCE_CAVEAT_MISSING");
            if (claim.alternativeExplanations().isEmpty()) rejected.add("ALTERNATIVE_EXPLANATION_MISSING");
        }

        matchDeclared("FIELD_NOT_AUTHORIZED", claim.inputFields(), capability == null
            ? Set.of() : capability.declaredFields(), rejected);
        matchDeclaredValue("UNIT_MISMATCH", claim.outputUnit(), capability == null
            ? Set.of() : capability.declaredUnits(), rejected);
        matchDeclaredValue("GRAIN_MISMATCH", claim.grain(), capability == null
            ? Set.of() : capability.declaredGrains(), rejected);
        matchDeclaredValue("TIME_SCOPE_MISMATCH", claim.timeScope(), capability == null
            ? Set.of() : capability.declaredTimeScopes(), rejected);
        matchDeclaredValue("POPULATION_SCOPE_MISMATCH", claim.populationScope(), capability == null
            ? Set.of() : capability.declaredPopulationScopes(), rejected);
        if (evidence != null) {
            matchEvidenceValue("EVIDENCE_GRAIN_MISMATCH", claim.grain(), evidence.grain(), rejected);
            matchEvidenceValue("EVIDENCE_TIME_SCOPE_MISMATCH", claim.timeScope(), evidence.timeScope(), rejected);
            matchEvidenceValue("EVIDENCE_POPULATION_SCOPE_MISMATCH", claim.populationScope(),
                evidence.populationScope(), rejected);
        }
        return decision(rejected);
    }

    private void matchDeclared(String code, Set<String> claimed, Set<String> declared,
                               List<String> rejected) {
        if (!declared.isEmpty() && !declared.containsAll(claimed)) rejected.add(code);
    }

    private void matchDeclaredValue(String code, String claimed, Set<String> declared,
                                    List<String> rejected) {
        if (!declared.isEmpty() && !claimed.isBlank() && !declared.contains(claimed)) rejected.add(code);
    }

    private void matchEvidenceValue(String code, String claimed, String observed,
                                    List<String> rejected) {
        if (!observed.isBlank() && !claimed.isBlank() && !observed.equals(claimed)) rejected.add(code);
    }

    private CapabilityEvidenceClaimContract.Admission decision(List<String> rejected) {
        return new CapabilityEvidenceClaimContract.Admission(
            rejected.isEmpty(), rejected.stream().distinct().toList());
    }
}
