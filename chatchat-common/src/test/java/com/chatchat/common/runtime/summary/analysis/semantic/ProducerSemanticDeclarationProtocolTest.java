package com.chatchat.common.runtime.summary.analysis.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProducerSemanticDeclarationProtocolTest {

    @Test
    void canonicalizesAliasAndReportsProducerReadinessWithoutBusinessInterpretation() {
        Map<String, Object> metadata = Map.of("producer_semantic_declaration", Map.of(
            "schemaVersion", ProducerSemanticDeclaration.SCHEMA_VERSION,
            "capabilityId", "generic-observation",
            "allowedOperations", List.of("observe")));

        Map<String, Object> normalized = ProducerSemanticDeclarationProtocol.canonicalizeOwnerMetadata(metadata);

        assertThat(normalized).containsKey(ProducerSemanticDeclarationProtocol.CONTEXT_KEY)
            .doesNotContainKey("producer_semantic_declaration");
        assertThat(ProducerSemanticDeclarationProtocol.readiness(normalized))
            .isEqualTo(ProducerSemanticDeclarationProtocol.Readiness.DECLARED_OBSERVE_ONLY);
        assertThat(ProducerSemanticDeclarationProtocol.readiness(Map.of()))
            .isEqualTo(ProducerSemanticDeclarationProtocol.Readiness.MISSING_OBSERVE_ONLY);
    }

    @Test
    void parsesAndProjectsAProducerOwnedDomainNeutralDeclaration() {
        ProducerSemanticDeclaration declaration = ProducerSemanticDeclarationProtocol.parse(declaration());

        assertThat(declaration.capabilityId()).isEqualTo("generic.metric.comparison");
        assertThat(declaration.toAnalysisContextSections().toString())
            .contains("allowedOperations=[COMPARE, OBSERVE]", "metric_a", "metric_b",
                "producer-declared comparison", "entity-period", "returned population");
    }

    @Test
    void rejectsBroadOperationWithoutAConcreteProducerRule() {
        Map<String, Object> invalid = new java.util.LinkedHashMap<>(declaration());
        invalid.put("rules", List.of());

        assertThatThrownBy(() -> ProducerSemanticDeclarationProtocol.parse(invalid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("require explicit rules");
    }

    @Test
    void mergesDeclarationWithoutReplacingSourceIdentityOrBusinessMetadata() {
        Map<String, Object> result = ProducerSemanticDeclarationProtocol.mergeIntoAnalysisContext(
            Map.of("source", Map.of("id", "source-1"), "business", Map.of("label", "Dataset")),
            declaration());

        assertThat(result.get("source").toString()).contains("source-1");
        assertThat(result.get("business").toString()).contains("Dataset");
        assertThat(result.get("capability").toString()).contains("generic.metric.comparison");
    }

    private Map<String, Object> declaration() {
        return Map.of(
            "schemaVersion", ProducerSemanticDeclaration.SCHEMA_VERSION,
            "capabilityId", "generic.metric.comparison",
            "allowedOperations", List.of("OBSERVE", "COMPARE"),
            "fields", List.of(
                Map.of("name", "metric_a", "meaning", "first producer metric", "unit", "unit-x"),
                Map.of("name", "metric_b", "meaning", "second producer metric", "unit", "unit-x")),
            "evidenceScope", Map.of(
                "grain", "entity-period", "timeScope", "returned period",
                "populationScope", "returned population", "completeness", "PARTIAL"),
            "rules", List.of(Map.of(
                "ruleId", "compare-a-b", "operation", "COMPARE",
                "basis", "producer-declared comparison", "inputFields", List.of("metric_a", "metric_b"),
                "outputUnit", "unit-x", "grain", "entity-period",
                "timeScope", "returned period", "populationScope", "returned population")));
    }
}
