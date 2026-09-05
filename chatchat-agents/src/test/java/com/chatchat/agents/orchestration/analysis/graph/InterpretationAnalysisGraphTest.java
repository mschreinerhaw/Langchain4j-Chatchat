package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;
import com.chatchat.agents.runtime.plan.execution.AgentPlanSuspendedException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static com.chatchat.agents.orchestration.analysis.graph.InterpretationAnalysisGraph.Phase.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class InterpretationAnalysisGraphTest {
    @Test void refinementReentersPlanningAndPublishesOnlyOnce() {
        var visited = new ArrayList<InterpretationAnalysisGraph.Phase>();
        var rounds = new AtomicInteger();
        Map<String, Object> metadata = new LinkedHashMap<>();
        new InterpretationAnalysisGraph().execute(phase -> {
            visited.add(phase);
            return switch (phase) {
                case PREPARE -> INITIAL_DATA;
                case INITIAL_DATA -> INITIAL_ANALYSIS;
                case INITIAL_ANALYSIS -> PREPARE_REFINEMENT;
                case PREPARE_REFINEMENT -> REFINEMENT_GATE;
                case REFINEMENT_GATE -> REFINEMENT_PLAN;
                case REFINEMENT_PLAN -> REFINEMENT_DATA;
                case REFINEMENT_DATA -> REFINEMENT_ANALYSIS;
                case REFINEMENT_ANALYSIS -> rounds.incrementAndGet() == 2 ? FINAL_REFINED : REFINEMENT_GATE;
                case FINAL_REFINED -> END;
                default -> throw new AssertionError("Unexpected phase: " + phase);
            };
        }, metadata);
        assertThat(visited.stream().filter(p -> p == FINAL_REFINED)).hasSize(1);
        assertThat(rounds).hasValue(2);
        assertThat(metadata).containsEntry("analysisPipelinePhase", "FINAL_REFINED");
        assertThat((List<?>) metadata.get("analysisPipelineNodes")).hasSize(13);
    }

    @Test void invalidTransitionCannotSkipDataAdmission() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        assertThatThrownBy(() -> new InterpretationAnalysisGraph().execute(p -> FINAL_INITIAL, metadata))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid analysis transition");
        assertThat(metadata).containsEntry("analysisPipelineStatus", "FAILED");
    }

    @Test void durableSuspensionIsNotRetriedOrWrapped() {
        var suspension = new AgentPlanSuspendedException(mock(AgentPlanPipelineContinuation.class));
        var visited = new ArrayList<InterpretationAnalysisGraph.Phase>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        assertThatThrownBy(() -> new InterpretationAnalysisGraph().execute(phase -> {
            visited.add(phase);
            if (phase == PREPARE) return INITIAL_DATA;
            throw suspension;
        }, metadata)).isSameAs(suspension);
        assertThat(visited).containsExactly(PREPARE, INITIAL_DATA);
        assertThat(metadata).containsEntry("analysisPipelineStatus", "SUSPENDED");
    }

    @Test void corruptedRefinementBudgetCannotRunForeverOrReportSuccess() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        assertThatThrownBy(() -> new InterpretationAnalysisGraph().execute(phase -> switch (phase) {
            case PREPARE -> INITIAL_DATA;
            case INITIAL_DATA -> INITIAL_ANALYSIS;
            case INITIAL_ANALYSIS -> PREPARE_REFINEMENT;
            case PREPARE_REFINEMENT -> REFINEMENT_GATE;
            case REFINEMENT_GATE -> REFINEMENT_PLAN;
            case REFINEMENT_PLAN -> REFINEMENT_GATE;
            default -> throw new AssertionError("Must not publish");
        }, metadata)).isInstanceOf(RuntimeException.class);
        assertThat(metadata).containsEntry("analysisPipelineStatus", "FAILED");
        assertThat((List<?>) metadata.get("analysisPipelineNodes")).hasSizeLessThanOrEqualTo(512);
    }
}
