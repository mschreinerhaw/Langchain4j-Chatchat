package com.chatchat.common.runtime.summary.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataAnalysisLayerGovernanceContractTest {

    @Test
    void createsStableLayerAdmissionLineageAndRepairContracts() {
        DataAnalysisLayerGovernanceContract.Admission admission =
            new DataAnalysisLayerGovernanceContract.Admission(
                "", "reducer-report-1",
                DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT,
                DataAnalysisLayerGovernanceContract.State.ADMITTED,
                true, List.of(), List.of("worker-report-1"), List.of("claim-1"));
        DataAnalysisLayerGovernanceContract.LineageEdge lineage =
            new DataAnalysisLayerGovernanceContract.LineageEdge(
                "", "worker-report-1", "reducer-report-1",
                DataAnalysisLayerGovernanceContract.Relation.DERIVED_FROM,
                DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT);
        DataAnalysisLayerGovernanceContract.RepairRequest repair =
            new DataAnalysisLayerGovernanceContract.RepairRequest(
                "", "reducer-report-1",
                DataAnalysisLayerGovernanceContract.Layer.WORKER_REPORT,
                DataAnalysisLayerGovernanceContract.RepairRoute.REPLAN_EVIDENCE,
                "Acquire the missing comparison baseline.",
                List.of("comparison baseline missing"), List.of("time-series"),
                List.of("baseline"), "last month", "day",
                DataAnalysisLayerGovernanceContract.Layer.WORKER_REPORT);
        DataAnalysisLayerGovernanceContract.ClaimTransition transition =
            new DataAnalysisLayerGovernanceContract.ClaimTransition(
                "", "claim-1", DataAnalysisLayerGovernanceContract.Layer.DRIVER_DECISION,
                DataAnalysisLayerGovernanceContract.State.SYNTHESIZED,
                DataAnalysisLayerGovernanceContract.State.PUBLISHED,
                List.of("reducer-report-1"), "Selected for governed publication.");
        DataAnalysisLayerGovernanceContract.ClaimRevision revision =
            new DataAnalysisLayerGovernanceContract.ClaimRevision(
                "", "claim-1", 2, "claim-revision-1", "evidence-v2",
                DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT,
                DataAnalysisLayerGovernanceContract.State.SYNTHESIZED);

        assertThat(admission.toMap())
            .containsEntry("schemaVersion", DataAnalysisLayerGovernanceContract.SCHEMA_VERSION)
            .containsEntry("state", "ADMITTED")
            .containsEntry("admitted", true);
        assertThat(lineage.toMap())
            .containsEntry("schemaVersion",
                DataAnalysisLayerGovernanceContract.LINEAGE_SCHEMA_VERSION)
            .containsEntry("relation", "DERIVED_FROM");
        assertThat(repair.toMap())
            .containsEntry("schemaVersion",
                DataAnalysisLayerGovernanceContract.REPAIR_SCHEMA_VERSION)
            .containsEntry("route", "REPLAN_EVIDENCE")
            .containsEntry("resumeAt", "WORKER_REPORT");
        assertThat(transition.toMap())
            .containsEntry("fromState", "SYNTHESIZED")
            .containsEntry("toState", "PUBLISHED");
        assertThat(revision.toMap()).containsEntry("revision", 2)
            .containsEntry("parentRevisionId", "claim-revision-1")
            .containsEntry("evidenceVersion", "evidence-v2");
        assertThat(admission.admissionId()).startsWith("analysis-admission:");
        assertThat(lineage.edgeId()).startsWith("analysis-lineage:");
        assertThat(repair.requestId()).startsWith("analysis-repair:");
        assertThat(transition.transitionId()).startsWith("analysis-claim-transition:");
        assertThat(revision.revisionId()).startsWith("analysis-claim-revision:");
    }
}
