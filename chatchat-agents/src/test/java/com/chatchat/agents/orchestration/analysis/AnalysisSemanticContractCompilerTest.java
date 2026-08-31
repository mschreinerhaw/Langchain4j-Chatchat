package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.contract.AnalysisSemanticContractCompiler;


import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisSemanticContractCompilerTest {

    private final AnalysisSemanticContractCompiler compiler =
        new AnalysisSemanticContractCompiler();

    @Test
    void treatsDerivedRecordShapeAsSemanticallyUndeclared() {
        Map<String, Object> result = compiler.compile(Map.of(
            "schema", Map.of("fields", List.of(Map.of("name", "total"))),
            "contextCompleteness", Map.of(
                "suppliedSections", List.of(),
                "derivedFieldNamesOnly", true)));

        assertThat(result).containsEntry("semanticAuthority", "UNDECLARED");
        assertThat(result).doesNotContainKey("schema");
        assertThat(result.get("runtimeInvariants").toString())
            .contains("NO_UNDECLARED_AGGREGATION_OR_ADDITIVITY");
    }

    @Test
    void preservesProducerDeclarationsWithoutInventingFieldSemantics() {
        Map<String, Object> declaredSemantics = Map.of(
            "fieldPolicies", List.of(Map.of(
                "field", "metric_a", "aggregation", "LAST", "scope", "DATASET")));
        Map<String, Object> result = compiler.compile(Map.of(
            "semantics", declaredSemantics,
            "analysisPolicy", Map.of("mode", "ANALYZE"),
            "contextCompleteness", Map.of(
                "suppliedSections", List.of("semantics", "analysisPolicy"))));

        assertThat(result).containsEntry("semanticAuthority", "PRODUCER_DECLARED");
        assertThat(result.get("semantics")).isEqualTo(declaredSemantics);
        assertThat(result.toString()).doesNotContain("fund", "ETF", "capitalFlow");
    }

    @Test
    void preservesCapabilityAsProducerSemanticAuthority() {
        Map<String, Object> capability = Map.of(
            "capabilityId", "generic-analysis",
            "allowedOperations", List.of("OBSERVE", "COMPARE"));

        Map<String, Object> result = compiler.compile(Map.of(
            "capability", capability,
            "contextCompleteness", Map.of("suppliedSections", List.of("capability"))));

        assertThat(result).containsEntry("semanticAuthority", "PRODUCER_DECLARED");
        assertThat(result.get("capability")).isEqualTo(capability);
        assertThat(result.get("declaredSections").toString()).contains("capability");
    }
}
