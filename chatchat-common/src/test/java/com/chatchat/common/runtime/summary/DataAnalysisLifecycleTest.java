package com.chatchat.common.runtime.summary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAnalysisLifecycleTest {

    @Test
    void enforcesRelationshipDispatchReconciliationAndSummaryOrder() {
        DataAnalysisLifecycle lifecycle = DataAnalysisLifecycle.begin("analysis-1", 4)
            .relationshipsEstablished(3, 1)
            .datasetsDispatched(4)
            .workersReconciled(3, 1)
            .finalSummaryCompleted(3);

        assertThat(lifecycle.complete()).isTrue();
        assertThat(lifecycle.completedStages()).containsExactly(
            DataAnalysisLifecycle.Stage.RELATIONSHIPS_ESTABLISHED,
            DataAnalysisLifecycle.Stage.DATASETS_DISPATCHED,
            DataAnalysisLifecycle.Stage.WORKERS_RECONCILED,
            DataAnalysisLifecycle.Stage.FINAL_SUMMARY_COMPLETED);
        assertThat(lifecycle.toMap())
            .containsEntry("datasetCount", 4)
            .containsEntry("terminalTaskCount", 4)
            .containsEntry("successfulTaskCount", 3)
            .containsEntry("failedTaskCount", 1)
            .containsEntry("complete", true);
    }

    @Test
    void rejectsSkippedStagesAndIncompleteWorkerAccounting() {
        DataAnalysisLifecycle started = DataAnalysisLifecycle.begin("analysis-2", 4);
        assertThatThrownBy(() -> started.datasetsDispatched(4))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RELATIONSHIPS_ESTABLISHED");

        DataAnalysisLifecycle dispatched = started.relationshipsEstablished(4, 0)
            .datasetsDispatched(4);
        assertThatThrownBy(() -> dispatched.workersReconciled(2, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("every dispatched task");
        assertThatThrownBy(() -> dispatched.finalSummaryCompleted(1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WORKERS_RECONCILED");
    }

    @Test
    void rejectsForgedOrInconsistentPersistedSnapshots() {
        assertThatThrownBy(() -> new DataAnalysisLifecycle(
            DataAnalysisLifecycle.SCHEMA_VERSION, "forged", DataAnalysisLifecycle.Stage.FINAL_SUMMARY_COMPLETED,
            4, 1, 0, 4, 3, 3, 0, 1,
            java.util.List.of(DataAnalysisLifecycle.Stage.FINAL_SUMMARY_COMPLETED)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exact lifecycle prefix");
    }
}
