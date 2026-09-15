package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.dispatch.LocalAnalysisTaskDispatcher;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;


import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.model.ModelSummaryProgress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

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

            assertThat(progress.stream().filter(event -> "WORKER_EXECUTION_METRIC".equals(event.stage())))
                .hasSize(2).allSatisfy(event -> {
                    assertThat(((Number) event.details().get("queueTimeMs")).longValue()).isNotNegative();
                    assertThat(((Number) event.details().get("executionTimeMs")).longValue()).isNotNegative();
                    assertThat(event.details()).containsEntry("configuredParallelism", 2);
                });
            assertThat(slowResult.status()).isEqualTo("SUCCESS");
            assertThat(fastResult.status()).isEqualTo("SUCCESS");
            assertThat(fastResult.summary()).isSameAs(fastSummary);
            assertThat(progress).anySatisfy(event -> assertThat(event)
                .extracting(ModelSummaryProgress::stage,
                    ModelSummaryProgress::workReference)
                .containsExactly("WORKER_HEARTBEAT", "slow"));
        }
    }

    @Test
    void failsTaskWhenWorkerHeartbeatLeaseExpires() {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant-1", "run-1", "request-1", "conversation-1", "user-1");
        AnalysisTask stalled = task(scope, "stalled", 1, 80L);
        CountDownLatch neverReleased = new CountDownLatch(1);
        LocalAnalysisTaskDispatcher dispatcher = new LocalAnalysisTaskDispatcher(1, 20L);

        try (ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> batch = dispatcher.dispatch(
            List.of(stalled),
            (task, reporter) -> {
                try {
                    neverReleased.await();
                    return null;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("worker interrupted", interrupted);
                }
            },
            () -> false,
            progress -> {
                if ("WORKER_HEARTBEAT".equals(progress.stage())) {
                    try {
                        neverReleased.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            })) {
            AnalysisTaskResult result = batch.await(stalled.taskId());

            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.error()).contains("heartbeat lease expired");
        } finally {
            neverReleased.countDown();
        }
    }

    @Test
    void returnsCompletedResultAfterItsStoppedHeartbeatLeaseHasAgedOut() throws Exception {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant-1", "run-1", "request-1", "conversation-1", "user-1");
        AnalysisTask delayed = task(scope, "delayed-read", 1, 80L);
        AnalysisDatasetSummary summary = mock(AnalysisDatasetSummary.class);
        when(summary.isolationScope()).thenReturn(scope);
        when(summary.outcome()).thenReturn("SUCCESS");
        when(summary.chunks()).thenReturn(List.of());
        CountDownLatch completed = new CountDownLatch(1);
        LocalAnalysisTaskDispatcher dispatcher = new LocalAnalysisTaskDispatcher(1, 20L);

        try (ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> batch = dispatcher.dispatch(
            List.of(delayed),
            (task, reporter) -> {
                completed.countDown();
                return summary;
            },
            () -> false,
            progress -> { })) {
            completed.await();
            Thread.sleep(1_100L);

            AnalysisTaskResult result = batch.await(delayed.taskId());

            assertThat(result.status()).isEqualTo("SUCCESS");
            assertThat(result.summary()).isSameAs(summary);
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
