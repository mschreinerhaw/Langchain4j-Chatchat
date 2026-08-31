package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLifecycle;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisSynthesisCoordinatorTest {

    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
        "tenant-a", "user-a", "run-a", "request-a", "conversation-a");

    @Test
    void ownsCrossDatasetReductionAndCompletesLifecycleAfterWorkerReconciliation() {
        AgentRunResultAdapter adapter = mock(AgentRunResultAdapter.class);
        DeterministicInsightEngine insightEngine = mock(DeterministicInsightEngine.class);
        DeterministicInsightEngine.Result bundleResult = new DeterministicInsightEngine.Result(
            DeterministicInsightEngine.RESULT_VERSION, "executed", "bundle", "1",
            scope.toMap(), List.of(), List.of());
        when(insightEngine.analyzeBundle(any(), any())).thenReturn(bundleResult);
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            adapter, "agentRunId", mock(AnalysisSummaryGovernanceCoordinator.class),
            insightEngine, new AnswerCandidateCollector(), new HierarchicalAnalysisReducer());
        AnalysisSummaryResult datasetSummary = datasetSummary("dataset-a", "governed result");
        DatasetRelationshipPlan plan = DatasetRelationshipPlan.create(List.of(
            new DatasetRelationshipPlan.Dataset("dataset-a", Map.of())));
        DataAnalysisLifecycle lifecycle = DataAnalysisLifecycle.begin("analysis-a", 1)
            .relationshipsEstablished(1, 0).datasetsDispatched(1).workersReconciled(1, 0);

        AnalysisSynthesisCoordinator.HierarchicalSynthesisResult result =
            coordinator.synthesizeHierarchy(
                new AnalysisSynthesisCoordinator.HierarchicalSynthesisRequest(
                    prompt -> "unused", scope, plan, "analyze returned data",
                    List.of(datasetSummary), List.of(), lifecycle,
                    Map.of("agentRunId", "run-a")));

        assertThat(result.crossDatasetInsights()).isSameAs(bundleResult);
        assertThat(result.hierarchy().finalInputs()).containsExactly(datasetSummary);
        assertThat(result.lifecycle().complete()).isTrue();
        assertThat(result.lifecycle().finalInputCount()).isEqualTo(1);
        verify(adapter).recordRuntimeObservation(any(), any(), any(), any(), any());
    }

    @Test
    void executesFinalModelCallThenAppliesGuardsAndGovernanceExactlyOnce() {
        AgentRunResultAdapter adapter = mock(AgentRunResultAdapter.class);
        AnalysisSummaryGovernanceCoordinator governance = mock(AnalysisSummaryGovernanceCoordinator.class);
        AnalysisSummaryResult governed = AnalysisSummaryResult.finalSummary(
            scope, "completed", "guarded answer", "MODEL_FINAL_SUMMARY", Map.of(), List.of());
        when(governance.finalizeSummary(any())).thenReturn(governed);
        AnswerCandidateCollector candidates = new AnswerCandidateCollector();
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            adapter, "agentRunId", governance, new DeterministicInsightEngine(), candidates,
            new HierarchicalAnalysisReducer());
        Map<String, Object> metadata = new LinkedHashMap<>();
        ChatModel model = mock(ChatModel.class);
        when(model.chat("prompt")).thenReturn("model answer");

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            request(model, metadata, candidate -> "guarded answer", () -> "fallback", true));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).isEqualTo("guarded answer");
        assertThat(metadata).containsEntry("interpretationPlanSummaryGenerated", true);
        assertThat(candidates.hasCandidates(metadata)).isTrue();
        verify(governance).finalizeSummary(any());
        verify(adapter).recordRuntimeObservation(any(), any(), any(), any(), any());
    }

    @Test
    void modelFailureUsesGovernedDeterministicFallbackWhenPolicyAllowsIt() {
        AnalysisSummaryGovernanceCoordinator governance = mock(AnalysisSummaryGovernanceCoordinator.class);
        AnalysisSummaryResult governed = AnalysisSummaryResult.finalSummary(
            scope, "completed", "fallback", "DETERMINISTIC_FINAL_FALLBACK", Map.of(), List.of());
        when(governance.finalizeSummary(any())).thenReturn(governed);
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", governance,
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        Map<String, Object> metadata = new LinkedHashMap<>();
        ChatModel failing = mock(ChatModel.class);
        when(failing.chat("prompt")).thenThrow(new IllegalStateException("model unavailable"));

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            request(failing, metadata, candidate -> candidate, () -> "fallback", true));

        assertThat(result.content()).isEqualTo("fallback");
        assertThat(metadata)
            .containsEntry("interpretationPlanDeterministicSummaryFallback", true)
            .containsEntry("executionStatus", "PARTIAL_RESULT_PRESENTED")
            .containsEntry("interpretationPlanSummaryGenerated", false)
            .containsEntry("interpretationPlanFinalResultProduced", true);
    }

    @Test
    void deadlineCancellationIsNotConvertedIntoAContentFallback() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId",
            mock(AnalysisSummaryGovernanceCoordinator.class),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        ChatModel timedOut = mock(ChatModel.class);
        when(timedOut.chat("prompt"))
            .thenThrow(new AgentDeadlineExceededException("deadline exhausted"));

        assertThatThrownBy(() -> coordinator.synthesizeFinal(
            request(timedOut, new LinkedHashMap<>(), candidate -> candidate,
                () -> "must not be used", true)))
            .isInstanceOf(AgentDeadlineExceededException.class)
            .hasMessageContaining("deadline exhausted");
    }

    @Test
    void presentationReplacesUngovernedDraftWithDriverSynthesis() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId",
            mock(AnalysisSummaryGovernanceCoordinator.class),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        AnalysisSummaryResult summary = datasetSummary("dataset-a", "完整的业务分析结论");
        Map<String, Object> metadata = new LinkedHashMap<>();

        String answer = coordinator.presentGovernedAnalysis("operational draft",
            new AnalysisSynthesisCoordinator.PresentationRequest(
                "raw appendix", List.of(List.of("完整")), 1, false,
                true, true, true, List.of(summary), List.of(summary), metadata));

        assertThat(answer).contains("数据分析总结", "完整的业务分析结论")
            .doesNotContain("operational draft", "raw appendix");
        assertThat(metadata)
            .containsEntry("governedNarrativeAnalysisReplacedOperationalDraft", true)
            .containsEntry("governedNarrativeAnalysisSource", "DRIVER_SYNTHESIS_INPUTS");
    }

    private AnalysisSynthesisCoordinator.FinalModelSynthesisRequest request(
        ChatModel model,
        Map<String, Object> metadata,
        java.util.function.UnaryOperator<String> guard,
        java.util.function.Supplier<String> fallback,
        boolean fallbackAllowed
    ) {
        return new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
            model, "prompt", "completed", "run-a", 2, 1, 3,
            fallbackAllowed, fallback, guard, "empty fallback",
            1, 1, true, true, true, 1, 0,
            List.of(), List.of(), Map.of("agentRunId", "run-a"), metadata);
    }

    private AnalysisSummaryResult datasetSummary(String dataset, String content) {
        return AnalysisSummaryResult.intermediateSummary(
            scope, "DATASET_SYNTHESIS", "dataset-summary#" + dataset, content,
            "MODEL_DATASET_REDUCE", Map.of("datasetReference", dataset), Map.of(),
            Map.of("complete", true), List.of(), Map.of());
    }
}
