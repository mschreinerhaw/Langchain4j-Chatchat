package com.chatchat.agents.runtime.toolcall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualToolArgumentResolverTest {

    private final ContextualToolArgumentResolver resolver = new ContextualToolArgumentResolver();

    @Test
    void recoversUniquePublishedAliasFromCompletedStructuredOutput() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "required", List.of("symbol"),
            "properties", Map.of("symbol", Map.of(
                "type", "string", "aliases", List.of("stockCode")))
        );

        ContextualToolArgumentResolver.Resolution result = resolver.resolve(
            new ContextualToolArgumentResolver.Request(
                Map.of(), schema, "analyze the holding",
                Map.of(3, Map.of("records", List.of(Map.of("stockCode", "600839")))))
        );

        assertThat(result.arguments()).containsEntry("symbol", "600839");
        assertThat(result.recovered()).singleElement().satisfies(item -> {
            assertThat(item.stepId()).isEqualTo(3);
            assertThat(item.outputPath()).isEqualTo("$.records[0].stockCode");
            assertThat(item.modelProposed()).isFalse();
        });
    }

    @Test
    void verifiesModelPointerByRereadingCompletedOutput() {
        Map<String, Object> schema = Map.of(
            "required", List.of("symbol"),
            "properties", Map.of("symbol", Map.of("type", "string"))
        );
        Map<String, Object> arguments = Map.of(
            ContextualToolArgumentResolver.MODEL_EVIDENCE_FIELD,
            List.of(Map.of(
                "parameter", "symbol",
                "source", "completed_step",
                "stepId", 4,
                "outputPath", "$.positions[1].code",
                "value", "invented-value"
            ))
        );

        ContextualToolArgumentResolver.Resolution result = resolver.resolve(
            new ContextualToolArgumentResolver.Request(
                arguments, schema, "", Map.of(4, Map.of(
                    "positions", List.of(Map.of("code", "000001"), Map.of("code", "300408")))))
        );

        assertThat(result.arguments()).containsEntry("symbol", "300408")
            .doesNotContainValue("invented-value");
        assertThat(result.recovered()).singleElement()
            .satisfies(item -> assertThat(item.modelProposed()).isTrue());
    }

    @Test
    void leavesAmbiguousScalarEvidenceUnresolvedWithoutModelSelection() {
        Map<String, Object> schema = Map.of(
            "required", List.of("symbol"),
            "properties", Map.of("symbol", Map.of(
                "type", "string", "aliases", List.of("stockCode")))
        );

        ContextualToolArgumentResolver.Resolution result = resolver.resolve(
            new ContextualToolArgumentResolver.Request(
                Map.of(), schema, "", Map.of(2, Map.of("records", List.of(
                    Map.of("stockCode", "600839"), Map.of("stockCode", "300408")))))
        );

        assertThat(result.arguments()).doesNotContainKey("symbol");
        assertThat(result.unresolvedRequiredFields()).containsExactly("symbol");
    }
}
