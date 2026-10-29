package com.chatchat.api.runtime.coding.evidence;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CodingSchemaResultVerifierTest {
    final Map<String,Object> reportRules = Map.of("rowSchema", Map.of("type", "object", "properties", Map.of(
        "analysisStatus", Map.of("type", "string"),
        "fieldProvenance", Map.of("type", "array", "items", Map.of("type", "object", "additionalProperties", true)),
        "visualReport", Map.of("type", "object", "additionalProperties", true))));
    Map<String,Object> report(String status, String fieldStatus, boolean chart) {
        return Map.of("analysisStatus", status, "fieldProvenance", List.of(Map.of("status", fieldStatus)),
            "visualReport", Map.of("charts", chart ? List.of(Map.of("id", "unsupported-chart")) : List.of()));
    }
    @Test void anUnconfirmedDisclaimerCannotAdmitChartsOrACompletedBusinessScope() {
        for (var row : List.of(report("INPUT_REQUIRED", "UNCONFIRMED", true), report("PARTIAL", "UNCONFIRMED", false))) {
            var result = verifier.verify(null, null, reportRules, List.of(row));
            assertThat(result.passed()).isFalse();
            assertThat(result.checks()).containsEntry("rowSchemaMatched", true).containsEntry("declaredReportScopeConsistent", false);
            assertThat(result.businessEvaluated()).isFalse();
        }
    }
    @Test void aStructureOnlyInputRequiredReportRemainsDeliverableWithoutBusinessCertification() {
        var result = verifier.verify(null, null, reportRules, List.of(report("INPUT_REQUIRED", "UNCONFIRMED", false)));
        assertThat(result.passed()).isTrue();
        assertThat(result.businessEvaluated()).isFalse();
    }
    @Test void confirmedDeclarationsDoNotBlockChartsOrPretendToIndependentlyVerifyThem() {
        var result = verifier.verify(null, null, reportRules, List.of(report("COMPLETE", "CONFIRMED", true)));
        assertThat(result.passed()).isTrue();
        assertThat(result.businessEvaluated()).isFalse();
    }
    final CodingSchemaResultVerifier verifier = new CodingSchemaResultVerifier();
    final Map<String, Object> rules = Map.of("rowSchema", Map.of("type", "object",
        "properties", Map.of("finding", Map.of("type", "string")), "required", List.of("finding")));

    @Test void lengthAloneCannotRejectOriginalTextAnalysisWithoutComparingItsInput() {
        var result = verifier.verify(null, null, rules, List.of(Map.of("finding", "private source\n".repeat(1100))));
        assertThat(result.passed()).isTrue();
        assertThat(result.checks()).containsEntry("rowSchemaMatched", true)
            .containsEntry("sourceCopyValidation", "COLLECTOR_IMMUTABLE_INPUT_COMPARISON");
        assertThat(result.checks().toString()).doesNotContain("private source");
    }

    @Test void conciseCalculatedFindingRemainsStructurallyValidWithoutClaimingBusinessValidation() {
        var result = verifier.verify(null, null, rules, List.of(Map.of("finding", "Computed from 20 delivered observations.")));
        assertThat(result.passed()).isTrue();
        assertThat(result.businessEvaluated()).isFalse();
    }

    @Test void nestedLongOriginalTextRemainsSubjectToSchemaAndSourceComparison() {
        var nestedRules = Map.<String, Object>of("rowSchema", Map.of("type", "object",
            "properties", Map.of("items", Map.of("type", "array", "items", Map.of("type", "string")))));
        assertThat(verifier.verify(null, null, nestedRules,
            List.of(Map.of("items", List.of("x".repeat(2049)))))).extracting(CodingEvidenceCollector.Validation::passed).isEqualTo(true);
    }
}
