package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisSummaryProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisCoverageCoordinatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void returnsCompleteEmptyCoverageWithoutDispatchWhenNoEvidenceDatasetsExist() {
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        AnalysisDispatchCoordinator dispatch = mock(AnalysisDispatchCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of()));
        AnalysisCoverageCoordinator coordinator = new AnalysisCoverageCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", evidence, dispatch,
            mock(DeterministicInsightEngine.class), mock(AnalysisSynthesisCoordinator.class),
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
        verify(dispatch, never()).dispatch(any());
    }
}
