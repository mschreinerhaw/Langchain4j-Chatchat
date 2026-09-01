package com.chatchat.agents.orchestration.analysis.dispatch;

import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisSummaryProtocol;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressListener;
import com.chatchat.common.runtime.summary.spi.ModelSummaryWorker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisDispatchCoordinatorTest {

    @Test
    void buildsStableDomainNeutralTasksWithoutNullEvidenceLocatorValues() {
        CapturingDispatcher dispatcher = new CapturingDispatcher(false);
        AnalysisDispatchCoordinator coordinator = coordinator(dispatcher);
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant", "user", "run", "request", "conversation");

        try (AnalysisDispatchCoordinator.DispatchBatch batch = coordinator.dispatch(
            new AnalysisDispatchCoordinator.DispatchRequest(null, "analyze all returned data",
                "model", List.of(new AnalysisDispatchCoordinator.DatasetInput(
                    "dataset-a", Map.of(), List.of(Map.of("arbitrary", 7)))),
                scope, Map.of(), () -> false))) {
            AnalysisTask task = dispatcher.tasks.get(0);
            assertThat(task.datasetReference()).isEqualTo("dataset-a");
            assertThat(task.records()).containsExactly(Map.of("arbitrary", 7));
            assertThat(task.evidenceLocator()).containsEntry("datasetReference", "dataset-a")
                .doesNotContainKey("canonicalPath");
            assertThat(task.idempotencyKey()).isEqualTo(task.taskId() + ":" + task.inputSha256());
            assertThat(task.inputSha256()).hasSize(64);
            assertThat(batch.taskCount()).isEqualTo(1);
        }
    }

    @Test
    void attachesRuntimeAgentRoleContextToEveryWorkerTask() {
        CapturingDispatcher dispatcher = new CapturingDispatcher(false);
        AnalysisDispatchCoordinator coordinator = coordinator(dispatcher);
        Map<String, Object> role = AgentRoleAnalysisContext.create(
            "Quality analyst", "Analyze service quality", List.of("Daily review"),
            List.of("quality"));

        try (AnalysisDispatchCoordinator.DispatchBatch ignored = coordinator.dispatch(
            new AnalysisDispatchCoordinator.DispatchRequest(null, "analyze returned data",
                "model", List.of(new AnalysisDispatchCoordinator.DatasetInput(
                    "dataset-a", Map.of(), List.of(Map.of("value", 7)))),
                GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
                Map.of(AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE, role), () -> false))) {
            assertThat(dispatcher.tasks.get(0).analysisContext())
                .containsEntry(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, role);
        }
    }

    @Test
    void rejectsWorkerResultWhoseInputHashDoesNotMatchDispatchedTask() {
        CapturingDispatcher dispatcher = new CapturingDispatcher(true);
        AnalysisDispatchCoordinator coordinator = coordinator(dispatcher);

        try (AnalysisDispatchCoordinator.DispatchBatch batch = coordinator.dispatch(
            new AnalysisDispatchCoordinator.DispatchRequest(null, "question", "model",
                List.of(new AnalysisDispatchCoordinator.DatasetInput(
                    "dataset-a", Map.of(), List.of(Map.of("value", 1)))),
                GovernanceIsolationScope.runtime("t", "u", "r", "q", "c"),
                Map.of(), () -> false))) {
            AnalysisDispatchCoordinator.Outcome outcome = batch.await("dataset-a");
            assertThat(outcome.success()).isFalse();
            assertThat(outcome.status()).isEqualTo("SUCCESS");
            assertThat(outcome.summary()).isNull();
        }
    }

    @SuppressWarnings("unchecked")
    private AnalysisDispatchCoordinator coordinator(CapturingDispatcher dispatcher) {
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol =
            mock(DataAnalysisSummaryProtocol.class);
        when(protocol.govern(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        return new AnalysisDispatchCoordinator(mock(AnalysisDatasetWorker.class),
            mock(AnalysisProgressRecorder.class),
            new AnalysisDispatchCoordinator.Configuration(100, 20_000, 100_000, 1, 1_000, 5_000),
            protocol, dispatcher);
    }

    private static final class CapturingDispatcher implements ModelSummaryDispatcher<
        AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> {
        private final List<AnalysisTask> tasks = new ArrayList<>();
        private final boolean mismatchHash;

        private CapturingDispatcher(boolean mismatchHash) {
            this.mismatchHash = mismatchHash;
        }

        @Override
        public DispatchBatch<AnalysisTaskResult> dispatch(List<AnalysisTask> values,
            ModelSummaryWorker<AnalysisTask, AnalysisDatasetSummary> worker,
            BooleanSupplier cancellationCheck, ModelSummaryProgressListener progressListener) {
            tasks.addAll(values);
            return new DispatchBatch<>() {
                @Override public AnalysisTaskResult await(String taskId) {
                    AnalysisTask task = tasks.stream().filter(t -> t.taskId().equals(taskId))
                        .findFirst().orElseThrow();
                    return new AnalysisTaskResult(AnalysisTaskResult.SCHEMA_VERSION, task.taskId(),
                        mismatchHash ? "different-hash" : task.inputSha256(), "worker", "SUCCESS",
                        1, 1, null, "");
                }
                @Override public int taskCount() { return values.size(); }
                @Override public int workerCount() { return 1; }
                @Override public String mode() { return "TEST"; }
                @Override public boolean cancel(String taskId) { return false; }
                @Override public boolean closed() { return false; }
                @Override public void close() {}
            };
        }
    }
}
