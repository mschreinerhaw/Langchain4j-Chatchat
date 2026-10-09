package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.nodes.synthesis.FinalSynthesisNode;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisCoverageCoordinatorTest {
    @Test
    @SuppressWarnings("unchecked")
    void nativeHarnessOwnsAnalysisWhileSourceFailuresRemainObservable() {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        var datasets = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
            new AnalysisEvidenceCoordinator.Dataset("source-" + index, Map.of(),
                List.of(Map.<String,Object>of("value", index, "sourceComplete", false)))).toList();
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(datasets,
            List.of(Map.of("datasetReference", "failed-source", "accountingStatus", "FAILED", "reason", "SOURCE_EXECUTION_FAILED"))));
        var insight = mock(DeterministicInsightEngine.class);
        var protocol = mock(DataAnalysisSummaryProtocol.class);
        var dispatch = mock(com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator.class);
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("Model-selected final analysis.");
        var coordinator = new AnalysisCoverageCoordinator(mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            insight, mock(FinalSynthesisNode.class), AnalysisEvidenceSpillStore.disabled(), dispatch,
            new AnalysisCoverageCoordinator.Configuration(1, 1000, 5000, true, 2, true, 1, 2000, true, 8));
        var metadata = new LinkedHashMap<String,Object>();
        var execution = new InterpretationPlanRuntime.ExecutionResult("completed", true, false, null, null, List.of(), Map.of(), 1);
        var result = coordinator.analyze(new AnalysisCoverageCoordinator.Request(model, "Question", execution, Map.of(), metadata,
            () -> false, () -> {}, GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"), protocol));
        assertThat(metadata).containsEntry("modelNativeHarnessActive", true)
            .containsEntry("modelNativeReportDraft", "Model-selected final analysis.")
            .containsEntry("recordAnalysisFailedDatasetCount", 1);
        assertThat(result.sourceContentComplete()).isFalse();
        assertThat(result.evidenceTraceComplete()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(insight, protocol, dispatch);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsCompleteEmptyCoverageWithoutDispatchWhenNoEvidenceDatasetsExist() {
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of()));
        AnalysisCoverageCoordinator coordinator = new AnalysisCoverageCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            mock(DeterministicInsightEngine.class), mock(FinalSynthesisNode.class),
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(1, 1_000, 5_000));
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, List.of(), Map.of(), 1);

        AnalysisCoverageCoordinator.CoverageBundle coverage = coordinator.analyze(
            new AnalysisCoverageCoordinator.Request(null, "question", result, Map.of(),
                new java.util.LinkedHashMap<>(), () -> false, () -> {},
                GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
                mock(DataAnalysisSummaryProtocol.class)));

        assertThat(coverage.returnedRecordCount()).isZero();
        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.evidenceTraceComplete()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptySourceIsPresentInCompletionAccounting() {
        Map<String, Object> exclusion = Map.of(
            "datasetReference", "empty-source",
            "accountingStatus", "EXCLUDED",
            "reason", "NO_NON_EMPTY_STRUCTURED_RECORDS");
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of(exclusion)));
        AnalysisCoverageCoordinator coordinator = coordinator(evidence);
        Map<String, Object> metadata = new LinkedHashMap<>();

        AnalysisCoverageCoordinator.CoverageBundle coverage = coordinator.analyze(
            request(metadata));

        assertThat(coverage.coverageComplete()).isTrue();
        assertThat((Map<String, Object>) metadata.get("datasetCompletionSnapshot"))
            .containsEntry("expectedDatasetCount", 1)
            .containsEntry("excludedDatasetCount", 1)
            .containsEntry("allRequiredDatasetsProcessed", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedSourceBlocksTraceCompletenessAndIsPresentInAccounting() {
        Map<String, Object> failure = Map.of(
            "datasetReference", "failed-source",
            "accountingStatus", "FAILED",
            "reason", "SOURCE_EXECUTION_FAILED");
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of(failure)));
        AnalysisCoverageCoordinator coordinator = coordinator(evidence);
        Map<String, Object> metadata = new LinkedHashMap<>();

        AnalysisCoverageCoordinator.CoverageBundle coverage = coordinator.analyze(
            request(metadata));

        assertThat(coverage.evidenceTraceComplete()).isFalse();
        assertThat(metadata).containsEntry("recordAnalysisAllWorkersFailed", true);
        assertThat((Map<String, Object>) metadata.get("datasetCompletionSnapshot"))
            .containsEntry("expectedDatasetCount", 1)
            .containsEntry("failedDatasetCount", 1)
            .containsEntry("allRequiredDatasetsProcessed", true);
    }

    private AnalysisCoverageCoordinator coordinator(AnalysisEvidenceCoordinator evidence) {
        return new AnalysisCoverageCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            mock(DeterministicInsightEngine.class), mock(FinalSynthesisNode.class),
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(1, 1_000, 5_000));
    }

    private AnalysisCoverageCoordinator.Request request(Map<String, Object> metadata) {
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, List.of(), Map.of(), 1);
        return new AnalysisCoverageCoordinator.Request(null, "question", result, Map.of(),
            metadata, () -> false, () -> {},
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            mock(DataAnalysisSummaryProtocol.class));
    }
}
