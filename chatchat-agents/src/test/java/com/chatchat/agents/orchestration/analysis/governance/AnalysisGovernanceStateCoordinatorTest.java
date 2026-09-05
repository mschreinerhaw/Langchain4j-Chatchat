package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.governance.DataAnalysisLayerGovernanceContract;
import com.chatchat.common.runtime.summary.analysis.governance.DataAnalysisLineageGraph;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisGovernanceStateCoordinatorTest {

    @Test
    void persistsRepairTerminationClaimRevisionsAndQueryableLineageAcrossRounds() {
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant", "user", "run", "request", "conversation");
        AnalysisSummaryResult worker = AnalysisSummaryResult.chunk(
            scope, Map.of("datasetReference", "dataset", "chunkIndex", 1), Map.of(),
            "worker", "MODEL_SUMMARY", Map.of("evidenceId", "evidence-1"));
        AnalysisSummaryResult reducer = AnalysisSummaryResult.intermediateSummary(
            scope, "DATASET_SYNTHESIS", "reducer-1", "reducer", "MODEL_DATASET_REDUCE",
            Map.of(), Map.of(), Map.of(), List.of(worker), Map.of()).withEvidence(Map.of(
                "analysisReportAdmission", Map.of("admittedClaimIds", List.of("claim-1"))));
        Map<String, Object> repair = Map.of(
            "requestId", "repair-1", "missingEvidence", List.of("baseline"),
            "requiredFields", List.of("date"), "requiredCapabilities", List.of("timeseries"),
            "goal", "acquire baseline", "route", "REPLAN_EVIDENCE");
        Map<String, Object> metadata = new LinkedHashMap<>();
        AnalysisGovernanceStateCoordinator coordinator = new AnalysisGovernanceStateCoordinator();

        AnalysisGovernanceStateCoordinator.State first =
            coordinator.reconcile(List.of(reducer), List.of(repair), metadata);
        AnalysisGovernanceStateCoordinator.State second =
            coordinator.reconcile(List.of(reducer), List.of(repair), metadata);

        assertThat(first.activeRepairRequests()).hasSize(1);
        assertThat(second.activeRepairRequests()).isEmpty();
        assertThat(second.repairExecutionStates().get(0))
            .containsEntry("terminalReason", "NO_NEW_EVIDENCE");
        assertThat((List<?>) metadata.get("analysisRepairExecutionHistory")).hasSize(2);
        assertThat((List<?>) metadata.get("analysisClaimRevisionHistory")).hasSize(2);
        assertThat(second.claimRevisions().get(0))
            .containsEntry("revision", 2).containsKey("parentRevisionId");

        DataAnalysisLineageGraph graph = DataAnalysisLineageGraph.fromMap(
            metadata.get("analysisLineageGraph"));
        assertThat(graph.supportingEvidence(reducer.resultId()))
            .extracting(DataAnalysisLineageGraph.Node::nodeId).containsExactly("evidence-1");
    }

    @Test
    void marksAnActiveRepairResolvedWhenTheGapDisappears() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        AnalysisGovernanceStateCoordinator coordinator = new AnalysisGovernanceStateCoordinator();
        Map<String, Object> repair = Map.of(
            "requestId", "repair-1", "missingEvidence", List.of("baseline"));

        coordinator.reconcile(List.of(), List.of(repair), metadata);
        AnalysisGovernanceStateCoordinator.State resolved =
            coordinator.reconcile(List.of(), List.of(), metadata);

        assertThat(resolved.repairExecutionStates()).singleElement().satisfies(state ->
            assertThat(state).containsEntry("status", "RESOLVED")
                .containsEntry("terminalReason", "RESOLVED")
                .containsEntry("executable", false));
    }
}
