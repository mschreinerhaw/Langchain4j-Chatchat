package com.chatchat.agents.runtime.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpResultAnalysisBridgeTest {

    private final McpResultAnalysisBridge bridge = new McpResultAnalysisBridge();

    @Test
    void projectsEveryNonNullRuntimeDataShapeIntoAnalyzableRecords() {
        List<Object> arbitraryResults = List.of(
            Map.of("temperature", 42, "healthy", true),
            List.of("alpha", "beta"),
            "service healthy",
            73,
            true
        );

        for (Object result : arbitraryResults) {
            Map<String, Object> projection = bridge.analysisProjection("arbitrary-result", result);

            assertThat(projection).containsEntry("projectionContainsBusinessDataOnly", true);
            assertThat(projection.get("datasets"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isNotEmpty();
            assertThat(projection.toString()).contains("records");
        }
    }
}
