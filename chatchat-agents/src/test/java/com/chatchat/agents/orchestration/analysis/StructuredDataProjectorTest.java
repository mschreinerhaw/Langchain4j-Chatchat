package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.StructuredDataProjector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDataProjectorTest {

    private final StructuredDataProjector projector = new StructuredDataProjector();

    @Test
    void flattensNestedRowFactsAndCoalescesRepeatedChildCollections() {
        Object output = Map.of("data", Map.of("body", Map.of(
            "apps", Map.of("app", List.of(
                Map.of("id", "app-1", "usedResources", Map.of("memory", 4096, "vCores", 4)),
                Map.of("id", "app-2", "usedResources", Map.of("memory", 2048, "vCores", 2)))),
            "scheduler", Map.of("queueInfos", Map.of("queue", List.of(
                Map.of("queueName", "root-a", "queues", Map.of("queue", List.of(
                    Map.of("queueName", "a1", "usedCapacity", 70),
                    Map.of("queueName", "a2", "usedCapacity", 20)))),
                Map.of("queueName", "root-b", "queues", Map.of("queue", List.of(
                    Map.of("queueName", "b1", "usedCapacity", 10)))))))
        )));

        List<StructuredDataProjector.Dataset> datasets = projector.projectForAnalysis(output);

        assertThat(datasets).hasSize(3);
        assertThat(datasets).anySatisfy(dataset -> {
            assertThat(dataset.path()).isEqualTo("$.data.body.apps.app");
            assertThat(dataset.rows()).containsExactly(
                Map.of("id", "app-1", "usedResources.memory", 4096,
                    "usedResources.vCores", 4),
                Map.of("id", "app-2", "usedResources.memory", 2048,
                    "usedResources.vCores", 2));
        });
        assertThat(datasets).anySatisfy(dataset -> {
            assertThat(dataset.path())
                .isEqualTo("$.data.body.scheduler.queueInfos.queue[].queues.queue");
            assertThat(dataset.rows()).extracting(row -> row.get("queueName"))
                .containsExactly("a1", "a2", "b1");
        });
        assertThat(datasets.stream().flatMap(dataset -> dataset.columns().stream()))
            .doesNotContain("usedResources", "queues");
    }

    @Test
    void preservesRootSourceFactsScalarListsAndDuplicateValuesFromDifferentPaths() {
        Map<String, Object> sharedRows = Map.of("rows", List.of(Map.of(
            "value", 42,
            "source_url", "https://example.com/data"
        )));
        Object output = Map.of(
            "provider", "mcp-provider",
            "reference_urls", List.of("https://example.com/data", "https://example.com/method"),
            "primary", List.of(sharedRows),
            "canonical", List.of(sharedRows)
        );

        List<StructuredDataProjector.Dataset> datasets = projector.projectForAnalysis(output);

        assertThat(datasets).anySatisfy(dataset -> {
            assertThat(dataset.path()).isEqualTo("$");
            assertThat(dataset.rows()).containsExactly(Map.of(
                "provider", "mcp-provider",
                "reference_urls", List.of("https://example.com/data", "https://example.com/method")
            ));
        });
        assertThat(datasets).anySatisfy(dataset -> {
            assertThat(dataset.path()).isEqualTo("$.primary[].rows");
            assertThat(dataset.rows()).containsExactly(Map.of(
                "value", 42,
                "source_url", "https://example.com/data"
            ));
        });
        assertThat(datasets).anySatisfy(dataset ->
            assertThat(dataset.path()).isEqualTo("$.canonical[].rows"));
    }
}
