package com.chatchat.agents.orchestration.analysis.governance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetCompletionSnapshotTest {

    @Test
    void treatsTerminalFailuresAsProcessedAndExposesPartialStatus() {
        DatasetCompletionSnapshot snapshot = new DatasetCompletionSnapshot(
            4, 1, 2, 1, List.of("a", "b"), List.of("c"), List.of("empty"));

        assertThat(snapshot.allRequiredDatasetsProcessed()).isTrue();
        assertThat(snapshot.partial()).isTrue();
        assertThat(snapshot.toMap())
            .containsEntry("analyzedDatasetCount", 2)
            .containsEntry("failedDatasetReferences", List.of("c"))
            .containsEntry("excludedDatasetReferences", List.of("empty"));
    }
}
