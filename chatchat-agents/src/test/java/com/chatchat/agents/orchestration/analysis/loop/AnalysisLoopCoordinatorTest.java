package com.chatchat.agents.orchestration.analysis.loop;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.graph.AnalysisFlowState;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AnalysisLoopCoordinatorTest {
    private final AnalysisLoopCoordinator coordinator = new AnalysisLoopCoordinator(mock(AgentRunResultAdapter.class), "agentRunId");
    @Test void closingBudgetConvertsRetrievalToLimitedSynthesisWithoutLosingIteration() {
        Map<String,Object> metadata = new LinkedHashMap<>();
        Map<String,Object> evidence = Map.of("toolEvidence", List.of(Map.of("outputFacts", List.of("fact"))),
            "remainingMissing", List.of("previous period"));
        var decision = coordinator.decide(evidence, true, true, false, metadata);
        coordinator.recordDecision(decision, 3, Map.of(), metadata);
        assertThat(AnalysisFlowState.read(metadata).decision()).isEqualTo(EvidenceAugmentationPolicy.Decision.RETRIEVE_MORE);
        coordinator.recordStop(metadata, evidence, "evidence_iteration_limit", 3);
        assertThat(AnalysisFlowState.read(metadata).decision()).isEqualTo(EvidenceAugmentationPolicy.Decision.ANALYZE_WITH_LIMITATIONS);
        assertThat(AnalysisFlowState.read(metadata).loopClosed()).isTrue();
        assertThat(AnalysisFlowState.read(metadata).iteration()).isEqualTo(3);
        assertThat(metadata).containsEntry("evidenceAugmentationContinueLoop", false);
    }
    @Test void strictNoEvidenceAndAuthorizationDoNotBecomeBestEffortAnswers() {
        Map<String,Object> metadata = new LinkedHashMap<>(Map.of("evidenceRequirement", "STRICT"));
        var decision = coordinator.decide(Map.of(), false, true, false, metadata);
        coordinator.recordDecision(decision, 1, Map.of(), metadata);
        coordinator.recordStop(metadata, Map.of(), "budget_exhausted", 1);
        assertThat(AnalysisFlowState.read(metadata).decision()).isEqualTo(EvidenceAugmentationPolicy.Decision.EXACT_RESULT_UNAVAILABLE);
        assertThat(metadata).containsEntry("evidenceAugmentationAnswerAllowed", false);
        coordinator.recordDecision(coordinator.decide(Map.of(), false, true, true, metadata), 2, Map.of(), metadata);
        coordinator.recordStop(metadata, Map.of(), "authorization", 2);
        assertThat(AnalysisFlowState.read(metadata).decision()).isEqualTo(EvidenceAugmentationPolicy.Decision.BLOCKED_AUTHORIZATION);
    }
}
