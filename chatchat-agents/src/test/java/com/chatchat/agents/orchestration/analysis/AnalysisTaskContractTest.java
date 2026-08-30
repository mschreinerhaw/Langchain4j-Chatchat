package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;


import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.DataAnalysisScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisTaskContractTest {

    @Test
    void exposesStableSerializableDriverWorkerContractAndIdempotencyKey() {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant-1", "user-1", "run-1", "request-1", "conversation-1");
        AnalysisTask task = new AnalysisTask(
            AnalysisTask.SCHEMA_VERSION,
            "tenant-1:run-1:assets",
            "sha-256-input",
            scope,
            "assets",
            1,
            8,
            Map.of("source", Map.of("displayName", "Assets")),
            Map.of("store", "rocksdb", "evidenceId", "evidence-1"),
            List.of(Map.of("amount", 100)),
            "analyze assets",
            100,
            32_000,
            128_000,
            3,
            180_000,
            1);

        assertThat(task.idempotencyKey())
            .isEqualTo("tenant-1:run-1:assets:sha-256-input");
        assertThat(task.toMap())
            .containsEntry("modelName", "")
            .containsEntry("schemaVersion", "analysis_dataset_task.v1")
            .containsEntry("datasetCount", 8)
            .containsEntry("maximumChunkRows", 100)
            .containsEntry("maximumRetries", 3)
            .containsEntry("maximumAttempts", 4)
            .containsEntry("originalUserQuestion", "analyze assets")
            .containsEntry("evidenceLocator", Map.of(
                "store", "rocksdb", "evidenceId", "evidence-1"));
        assertThat(task.originalUserQuestion()).isEqualTo("analyze assets");
        assertThat(task.assignment().scope()).isEqualTo(DataAnalysisScope.DATASET);
        assertThat(task.assignment().inputReferences()).containsExactly("assets");
        assertThat(task.assignment().originalUserQuestion()).isEqualTo("analyze assets");
    }

    @Test
    void rejectsWorkerTaskWithoutOriginalUserQuestion() {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant-1", "user-1", "run-1", "request-1", "conversation-1");

        assertThatThrownBy(() -> new AnalysisTask(
            AnalysisTask.SCHEMA_VERSION, "task-1", "sha-256-input", scope,
            "assets", 1, 1, Map.of(), Map.of(), List.of(Map.of("amount", 100)),
            "  ", 100, 32_000, 128_000, 3, 180_000, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("originalUserQuestion");
    }
}
