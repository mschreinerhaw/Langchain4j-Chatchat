package com.chatchat.mcpserver.tool;

import com.chatchat.common.runtime.summary.analysis.semantic.ProducerSemanticDeclarationProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProducerSemanticMetadataPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalizesValidProducerMetadataAndExposesReadiness() {
        String normalized = ProducerSemanticMetadataPolicy.normalize(objectMapper, """
            {"owner":"source-team","producer_semantic_declaration":{
              "schemaVersion":"producer_semantic_declaration.v1",
              "capabilityId":"generic-series",
              "allowedOperations":["observe","trend"],
              "fields":[{"name":"value","unit":"unit-x"}],
              "evidenceScope":{"grain":"entity-day","timeScope":"returned-window",
                "populationScope":"returned-population","completeness":"producer-declared"},
              "rules":[{"ruleId":"series-trend","operation":"trend","basis":"ordered observations",
                "inputFields":["value"],"grain":"entity-day","timeScope":"returned-window",
                "populationScope":"returned-population"}]
            }}
            """, "governance");

        assertThat(normalized).contains("\"producerSemanticDeclaration\":")
            .doesNotContain("\"producer_semantic_declaration\":");
        assertThat(ProducerSemanticMetadataPolicy.readiness(objectMapper, normalized, "governance"))
            .isEqualTo(ProducerSemanticDeclarationProtocol.Readiness.DECLARED_ANALYTICAL);
    }

    @Test
    void rejectsAnalyticalPermissionWithoutProducerRule() {
        assertThatThrownBy(() -> ProducerSemanticMetadataPolicy.normalize(objectMapper, """
            {"producerSemanticDeclaration":{
              "schemaVersion":"producer_semantic_declaration.v1",
              "capabilityId":"generic-series",
              "allowedOperations":["OBSERVE","TREND"],
              "evidenceScope":{"grain":"entity-day","timeScope":"returned-window",
                "populationScope":"returned-population"}
            }}
            """, "governance"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-observe operations require explicit rules");
    }
}
