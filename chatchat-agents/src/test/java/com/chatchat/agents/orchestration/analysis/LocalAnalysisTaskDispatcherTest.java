package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.ModelSummaryProgress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalAnalysisTaskDispatcherTest {

    @Test
    void exposesBatchCancellationAndLifecycleThroughTheCommonPort() {
        LocalAnalysisTaskDispatcher dispatcher = new LocalAnalysisTaskDispatcher(1);
        ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> batch = dispatcher.dispatch(
            List.of(), (task, reporter) -> null, () -> false, progress -> { });

        assertThat(batch.closed()).isFalse();
        assertThat(batch.cancel("missing-task")).isFalse();
        batch.close();
        assertThat(batch.closed()).isTrue();
    }

    @Test
    void keepsWaitingPastTheHeartbeatLeaseWhileTheWorkerContinuesHeartbeating() {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant-1", "run-1", "request-1", "conversation-1", "user-1");
        AnalysisTask slow = task(scope, "slow", 1, 50L);
        AnalysisTask fast = task(scope, "fast", 2, 50L);
        AnalysisDatasetSummary fastSummary = mock(AnalysisDatasetSummary.class);
        when(fastSummary.isolationScope()).thenReturn(scope);
        when(fastSummary.outcome()).thenReturn("SUCCESS");
        when(fastSummary.chunks()).thenReturn(List.of());
        LocalAnalysisTaskDispatcher dispatcher = new LocalAnalysisTaskDispatcher(2, 250L);
        List<ModelSummaryProgress> progress = new CopyOnWriteArrayList<>();

        try (ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> batch = dispatcher.dispatch(
            List.of(slow, fast),
            (task, reporter) -> {
                if ("slow".equals(task.datasetReference())) {
                    try {
                        Thread.sleep(600L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("slow worker interrupted", interrupted);
                    }
                }
                return fastSummary;
            },
            () -> false,
            progress::add)) {
            AnalysisTaskResult slowResult = batch.await(slow.taskId());
            AnalysisTaskResult fastResult = batch.await(fast.taskId());

            assertThat(slowResult.status()).isEqualTo("SUCCESS");
            assertThat(fastResult.status()).isEqualTo("SUCCESS");
            assertThat(fastResult.summary()).isSameAs(fastSummary);
            assertThat(progress).anySatisfy(event -> assertThat(event)
                .extracting(ModelSummaryProgress::stage,
                    ModelSummaryProgress::workReference)
                .containsExactly("WORKER_HEARTBEAT", "slow"));
        }
    }

    private AnalysisTask task(
        GovernanceIsolationScope scope,
        String dataset,
        int datasetIndex,
        long timeoutMs
    ) {
        return new AnalysisTask(
            AnalysisTask.SCHEMA_VERSION,
            "task-" + dataset,
            "sha-" + dataset,
            scope,
            dataset,
            datasetIndex,
            2,
            Map.of("source", Map.of("displayName", dataset)),
            Map.of(),
            List.of(Map.of("value", dataset)),
            "analyze all available data",
            100,
            10_000,
            1_000_000,
            0,
            timeoutMs,
            1
        );
    }
}
