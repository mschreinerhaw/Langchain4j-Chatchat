package com.chatchat.api.runtime.coding.evidence;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CodingSchemaResultVerifierTest {
    final CodingSchemaResultVerifier verifier = new CodingSchemaResultVerifier();
    final Map<String, Object> rules = Map.of("rowSchema", Map.of("type", "object",
        "properties", Map.of("finding", Map.of("type", "string")), "required", List.of("finding")));

    @Test void bulkSourceDocumentCannotBecomeAnAdmittedFindingDespiteMatchingStringSchema() {
        var result = verifier.verify(null, null, rules, List.of(Map.of("finding", "private source\n".repeat(1100))));
        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).containsEntry("rowSchemaMatched", true).containsEntry("boundedResultText", false);
        assertThat(result.checks().toString()).doesNotContain("private source");
    }

    @Test void conciseCalculatedFindingRemainsStructurallyValidWithoutClaimingBusinessValidation() {
        var result = verifier.verify(null, null, rules, List.of(Map.of("finding", "Computed from 20 delivered observations.")));
        assertThat(result.passed()).isTrue();
        assertThat(result.businessEvaluated()).isFalse();
    }

    @Test void nestedCollectionsCannotBypassTheTextBoundary() {
        var nestedRules = Map.<String, Object>of("rowSchema", Map.of("type", "object",
            "properties", Map.of("items", Map.of("type", "array", "items", Map.of("type", "string")))));
        assertThat(verifier.verify(null, null, nestedRules,
            List.of(Map.of("items", List.of("x".repeat(2049)))))).extracting(CodingEvidenceCollector.Validation::passed).isEqualTo(false);
    }
}
