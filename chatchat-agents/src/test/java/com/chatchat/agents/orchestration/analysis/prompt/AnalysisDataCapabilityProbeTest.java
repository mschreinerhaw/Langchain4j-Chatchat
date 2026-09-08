package com.chatchat.agents.orchestration.analysis.prompt;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisDataCapabilityProbeTest {
    @Test void derivesMethodsOnlyFromDeclaredSchemaAndCapabilityMetadata() {
        var dataset = new Dataset("dataset-a", Map.of(
            "schema", Map.of("fields", List.of(
                Map.of("name", "period", "type", "string", "semanticRole", "TIME_DIMENSION"),
                Map.of("name", "group_key", "type", "string"),
                Map.of("name", "measure", "type", "string", "semanticRole", "METRIC", "unit", "count"))),
            "timePointCount", 3,
            "historicalBaseline", Map.of("status", "AVAILABLE"),
            "allowedOperations", List.of("AGGREGATE")),
            List.of(Map.of("privateValue", "must-not-be-read")));

        Map<String, Object> result = new AnalysisDataCapabilityProbe().probe(List.of(dataset));

        assertThat(result).containsEntry("rawValuesInspected", false);
        assertThat(result.toString()).contains("TREND", "BASELINE", "CONTRIBUTION",
            "measure", "group_key", "declaredPointCount=3")
            .doesNotContain("must-not-be-read");
    }
}
