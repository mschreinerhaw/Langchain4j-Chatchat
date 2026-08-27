package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.AnalysisTask;

import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisTaskContractTest {

    @Test
    void exposesStableSerializableDriverWorkerContractAndIdempotencyKey() {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant-1", "user-1", "run-1", "request-1", "conversation-1");
        AnalysisTask task = new AnalysisTask(
            AnalysisTask.SCHEMA_VERSION,
            "tenant-1:run-1:assets#chunk-1",
            "sha-256-input",
            scope,
            "assets",
            1,
            8,
            1,
            1,
            1,
            1,
            1,
            Map.of("source", Map.of("displayName", "Assets")),
            Map.of("store", "rocksdb", "evidenceId", "evidence-1"),
            List.of(Map.of("amount", 100)),
            "analyze assets",
            180_000,
            1);

        assertThat(task.idempotencyKey())
            .isEqualTo("tenant-1:run-1:assets#chunk-1:sha-256-input");
        assertThat(task.toMap())
            .containsEntry("schemaVersion", "analysis_task.v1")
            .containsEntry("datasetCount", 8)
            .containsEntry("evidenceLocator", Map.of(
                "store", "rocksdb", "evidenceId", "evidence-1"));
    }
}
