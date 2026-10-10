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
    @Test void explicitV2ModeCanThinkWithoutInitialDatasetsAndRetainItsCompletionIntent() {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(List.of(), List.of()));
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(com.chatchat.agents.protocol.ModelProtocolJson.compact(Map.of(
            "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "COMPLETE"), "reportMarkdown", "Working notes")));
        var coordinator = coordinator(evidence); var metadata = new LinkedHashMap<String,Object>();
        var base = request(metadata);
        var result = coordinator.analyze(new AnalysisCoverageCoordinator.Request(model, "Question", base.result(),
            Map.of("modelAnalysisProtocol", "model_native_analysis.v2"), metadata, () -> false, () -> {}, base.isolationScope(), base.summaryProtocol()));
        assertThat(metadata).containsEntry("publicationState", "NOT_REQUESTED").containsEntry("modelAnalysisOutput", "Working notes");
        assertThat(((Map<?,?>) metadata.get("modelDecision")).get("action")).isEqualTo("COMPLETE");
        assertThat(result.returnedRecordCount()).isZero();
        org.mockito.Mockito.verify(model).chat(any(String.class));
    }

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
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("Model-selected final analysis.");
        var coordinator = new AnalysisCoverageCoordinator(mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8));
        var metadata = new LinkedHashMap<String,Object>();
        var execution = new InterpretationPlanRuntime.ExecutionResult("completed", true, false, null, null, List.of(), Map.of(), 1);
        var result = coordinator.analyze(new AnalysisCoverageCoordinator.Request(model, "Question", execution, Map.of(), metadata,
            () -> false, () -> {}, GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"), protocol));
        assertThat(metadata).containsEntry("modelNativeHarnessActive", true)
            .containsEntry("modelNativeReportDraft", "Model-selected final analysis.")
            .containsEntry("recordAnalysisFailedDatasetCount", 1);
        assertThat(result.sourceContentComplete()).isFalse();
        assertThat(result.evidenceTraceComplete()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(insight, protocol);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsCompleteEmptyCoverageWithoutDispatchWhenNoEvidenceDatasetsExist() {
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of()));
        AnalysisCoverageCoordinator coordinator = new AnalysisCoverageCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8));
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
            "reason", "NO_PROJECTED_CONTENT");
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
        assertThat(metadata).containsEntry("recordAnalysisAllSourcesFailed", true);
        assertThat((Map<String, Object>) metadata.get("datasetCompletionSnapshot"))
            .containsEntry("expectedDatasetCount", 1)
            .containsEntry("failedDatasetCount", 1)
            .containsEntry("allRequiredDatasetsProcessed", true);
    }

    private AnalysisCoverageCoordinator coordinator(AnalysisEvidenceCoordinator evidence) {
        return new AnalysisCoverageCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8));
    }

    @Test
    void failureObservationDescribesExecutionFailureRatherThanContentFormat() {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(List.of(),
            List.of(Map.of("datasetReference", "opaque", "accountingStatus", "FAILED",
                "reason", "SOURCE_EXECUTION_FAILED", "executionStatus", "FAILED", "error", "SSH timeout"))));
        var adapter = mock(AgentRunResultAdapter.class);
        new AnalysisCoverageCoordinator(adapter, "agentRunId", evidence, AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8)).analyze(request(new LinkedHashMap<>()));
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(adapter).recordRuntimeObservation(any(), org.mockito.ArgumentMatchers.eq("agentRunId"),
            text.capture(), org.mockito.ArgumentMatchers.eq("analysis_summary_governance"), any());
        assertThat(text.getValue()).contains("SOURCE_EXECUTION_FAILED", "FAILED", "SSH timeout")
            .doesNotContain("结构化", "未返回");
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
