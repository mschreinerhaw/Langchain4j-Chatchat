package com.chatchat.common.runtime.summary.analysis.governance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataAnalysisLineageGraphTest {

    @Test
    void queriesEvidenceAncestorsAndSurvivesMetadataRoundTrip() {
        DataAnalysisLineageGraph graph = new DataAnalysisLineageGraph(
            List.of(
                new DataAnalysisLineageGraph.Node("evidence-1", "EVIDENCE", Map.of()),
                new DataAnalysisLineageGraph.Node("worker-1", "REPORT", Map.of()),
                new DataAnalysisLineageGraph.Node("reducer-1", "REPORT", Map.of()),
                new DataAnalysisLineageGraph.Node("driver-1", "DRIVER_DECISION", Map.of())),
            List.of(
                edge("evidence-1", "worker-1"), edge("worker-1", "reducer-1"),
                edge("reducer-1", "driver-1")));

        DataAnalysisLineageGraph restored = DataAnalysisLineageGraph.fromMap(graph.toMap());

        assertThat(restored.ancestors("driver-1")).extracting(DataAnalysisLineageGraph.Node::nodeId)
            .containsExactly("reducer-1", "worker-1", "evidence-1");
        assertThat(restored.supportingEvidence("driver-1"))
            .extracting(DataAnalysisLineageGraph.Node::nodeId).containsExactly("evidence-1");
        assertThat(restored.descendants("evidence-1"))
            .extracting(DataAnalysisLineageGraph.Node::nodeId)
            .containsExactly("worker-1", "reducer-1", "driver-1");
    }

    private DataAnalysisLayerGovernanceContract.LineageEdge edge(String from, String to) {
        return new DataAnalysisLayerGovernanceContract.LineageEdge(
            "", from, to, DataAnalysisLayerGovernanceContract.Relation.DERIVED_FROM,
            DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT);
    }
}
