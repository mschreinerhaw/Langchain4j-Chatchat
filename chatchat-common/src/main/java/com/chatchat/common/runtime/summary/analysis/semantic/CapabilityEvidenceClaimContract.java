package com.chatchat.common.runtime.summary.analysis.semantic;

import java.util.List;
import java.util.Set;

/**
 * Immutable, source-neutral semantic boundary between a declared capability, returned evidence,
 * and a proposed analytical claim. Runtime adapters populate it; business domains do not leak
 * into the admission policy.
 */
public final class CapabilityEvidenceClaimContract {

    public static final String SCHEMA_VERSION = "capability_evidence_claim.v1";

    private CapabilityEvidenceClaimContract() {
    }

    public record Capability(
        String capabilityId,
        String semanticAuthority,
        Set<SemanticOperation> allowedOperations,
        Set<String> declaredSemanticBasis,
        Set<String> declaredFields,
        Set<String> declaredUnits,
        Set<String> declaredGrains,
        Set<String> declaredTimeScopes,
        Set<String> declaredPopulationScopes
    ) {
        public Capability {
            capabilityId = text(capabilityId);
            semanticAuthority = text(semanticAuthority);
            allowedOperations = immutable(allowedOperations);
            declaredSemanticBasis = texts(declaredSemanticBasis);
            declaredFields = texts(declaredFields);
            declaredUnits = texts(declaredUnits);
            declaredGrains = texts(declaredGrains);
            declaredTimeScopes = texts(declaredTimeScopes);
            declaredPopulationScopes = texts(declaredPopulationScopes);
        }

        public boolean producerDeclared() {
            return "PRODUCER_DECLARED".equals(semanticAuthority);
        }
    }

    public record Evidence(
        String evidenceId,
        String capabilityId,
        Set<String> recordReferences,
        Set<String> exactValues,
        String grain,
        String timeScope,
        String populationScope,
        boolean complete
    ) {
        public Evidence {
            evidenceId = text(evidenceId);
            capabilityId = text(capabilityId);
            recordReferences = texts(recordReferences);
            exactValues = texts(exactValues);
            grain = text(grain);
            timeScope = text(timeScope);
            populationScope = text(populationScope);
        }
    }

    public record Claim(
        String claimClass,
        SemanticOperation operation,
        Set<String> evidenceReferences,
        Set<String> supportingValues,
        Set<String> semanticBasis,
        Set<String> inputFields,
        String outputUnit,
        String grain,
        String timeScope,
        String populationScope,
        String method,
        List<String> caveats,
        List<String> alternativeExplanations
    ) {
        public Claim {
            claimClass = text(claimClass);
            evidenceReferences = texts(evidenceReferences);
            supportingValues = texts(supportingValues);
            semanticBasis = texts(semanticBasis);
            inputFields = texts(inputFields);
            outputUnit = text(outputUnit);
            grain = text(grain);
            timeScope = text(timeScope);
            populationScope = text(populationScope);
            method = text(method);
            caveats = textList(caveats);
            alternativeExplanations = textList(alternativeExplanations);
        }
    }

    public record Admission(boolean admitted, List<String> rejectionCodes) {
        public Admission {
            rejectionCodes = textList(rejectionCodes);
            admitted = admitted && rejectionCodes.isEmpty();
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> Set<T> immutable(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static Set<String> texts(Set<String> values) {
        return values == null ? Set.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<String> textList(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }
}
