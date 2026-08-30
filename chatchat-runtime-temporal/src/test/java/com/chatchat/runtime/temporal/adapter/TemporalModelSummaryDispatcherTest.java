package com.chatchat.runtime.temporal.adapter;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.ModelSummaryProgress;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.core.TemporalWorkflowRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalModelSummaryDispatcherTest {

    private TestWorkflowEnvironment environment;
    private TemporalWorkflowRuntime runtime;
    private TemporalModelSummaryDispatcher dispatcher;
    private AtomicInteger executions;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        TemporalWorkflowProperties properties = new TemporalWorkflowProperties();
        properties.setTaskQueue("analysis-dispatch-test-" + System.nanoTime());
        properties.setActivityStartToCloseSeconds(60);
        properties.setActivityHeartbeatSeconds(5);
        properties.setActivityMaximumAttempts(1);
        properties.setAnalysisMaximumParallelism(2);
        properties.setAnalysisMaximumPayloadBytes(1_500_000L);
        executions = new AtomicInteger();
        runtime = new TemporalWorkflowRuntime(
            environment.getWorkflowClient(), environment.getWorkerFactory(),
            new ObjectMapper(), properties, null, null,
            () -> (task, progress) -> {
                executions.incrementAndGet();
                progress.report("CHUNK_STARTED", Map.of("chunkIndex", 1, "chunkCount", 1));
                return AnalysisTaskResult.failed(task, "test-analysis-service", 3L,
                    new IllegalStateException("expected test result"));
            });
        dispatcher = new TemporalModelSummaryDispatcher(
            environment.getWorkflowClient(), properties, new ObjectMapper(), runtime::startWorker);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.close();
        if (environment != null) environment.close();
    }

    @Test
    void schedulesEachDatasetAsActivityAndReturnsThroughDurableBarrier() {
        List<ModelSummaryProgress> progress = new ArrayList<>();
        var batch = dispatcher.dispatch(
            List.of(task("task-a", "dataset-a", 1), task("task-b", "dataset-b", 2)),
            (task, reporter) -> { throw new AssertionError("local callback must not execute"); },
            () -> false,
            progress::add);

        AnalysisTaskResult first = batch.await("task-a");
        AnalysisTaskResult second = batch.await("task-b");

        assertThat(batch.mode()).isEqualTo("TEMPORAL");
        assertThat(batch.workerCount()).isEqualTo(2);
        assertThat(executions).hasValue(2);
        assertThat(first.error()).isEqualTo("expected test result");
        assertThat(second.taskId()).isEqualTo("task-b");
        assertThat(progress).extracting(ModelSummaryProgress::stage)
            .contains("WORKER_CLAIMED", "DATASET_FAILED");
        batch.close();
        assertThat(batch.closed()).isTrue();
    }

    @Test
    void oversizedPayloadUsesBoundedLocalSafetyPath() {
        AnalysisTask large = new AnalysisTask(AnalysisTask.SCHEMA_VERSION, "large-task", "large-sha",
            GovernanceIsolationScope.runtime("tenant", "run-large", "request", "conversation", "user"),
            "large-dataset", 1, 1, Map.of(), Map.of(),
            List.of(Map.of("payload", "x".repeat(1_600_000))),
            "analyze", "test-model", 100, 2_000_000, 2_000_000, 0, 30_000L, 1);

        var batch = dispatcher.dispatch(List.of(large),
            (task, reporter) -> { throw new IllegalStateException("local safety path"); },
            () -> false, progress -> { });

        assertThat(batch.mode()).isEqualTo("LOCAL_PAYLOAD_FALLBACK");
        assertThat(batch.await("large-task").error()).isEqualTo("local safety path");
        assertThat(executions).hasValue(0);
        batch.close();
    }

    private AnalysisTask task(String taskId, String reference, int index) {
        return new AnalysisTask(AnalysisTask.SCHEMA_VERSION, taskId, "sha-" + taskId,
            GovernanceIsolationScope.runtime("tenant", "run", "request", "conversation", "user"),
            reference, index, 2, Map.of(), Map.of(), List.of(Map.of("value", index)),
            "analyze", "test-model", 100, 10_000, 100_000, 0, 30_000L, 1);
    }
}
