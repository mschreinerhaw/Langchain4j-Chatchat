package com.chatchat.agents.orchestration.analysis.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetExecutionRegistryTest {

    @Test
    void opensSynthesisOnlyAfterEveryExpectedDatasetIsTerminal() {
        DatasetExecutionRegistry registry = new DatasetExecutionRegistry();
        registry.expect("dataset-a", "run-1", 1, "source", "raw-a");
        registry.expect("dataset-b", "run-1", 2, "source", "raw-b");
        registry.analyzing("dataset-a");
        registry.analyzed("dataset-a", List.of("finding-a"), List.of("evidence-a"), false);

        assertThat(registry.synthesisReady()).isFalse();
        assertThat(registry.missingDatasetIds()).containsExactly("dataset-b");

        registry.failed("dataset-b", "unavailable");

        assertThat(registry.synthesisReady()).isTrue();
        assertThat(registry.snapshotMap()).containsEntry("expectedCount", 2);
        assertThat(registry.snapshot()).extracting(DatasetExecutionState::status)
            .containsExactly(DatasetAnalysisStatus.ANALYZED, DatasetAnalysisStatus.FAILED);
    }
}
