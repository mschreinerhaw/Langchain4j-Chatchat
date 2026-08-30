package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecutionPhaseContractTest {

    @Test
    void continuationAndPhaseCommandsNormalizeReplayRelevantCollections() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0", null, null,
            new InterpretationPlan.Plan(List.of()), null, null);
        PlanExecutionContinuation continuation = new PlanExecutionContinuation(
            null, " tenant-a::run-1 ", plan, List.of(3, 1, 3), List.of(),
            List.of(4, 4), List.of(2), -1, Map.of("purpose", "analysis"));

        assertThat(continuation.schemaVersion())
            .isEqualTo(PlanExecutionContinuation.SCHEMA_VERSION);
        assertThat(continuation.sessionId()).isEqualTo("tenant-a::run-1");
        assertThat(continuation.remainingStepIds()).containsExactly(1, 3);
        assertThat(continuation.decisionCount()).isZero();
        assertThat(new PlanModelArbitrationCommand(null, continuation,
            List.of(3, 1, 3), null).readyStepIds()).containsExactly(1, 3);
        assertThat(new PlanStepPreparationCommand(null, continuation,
            List.of(3, 1), Map.of()).schemaVersion())
            .isEqualTo(PlanStepPreparationCommand.SCHEMA_VERSION);
        assertThat(new PlanNodePersistenceCommand(null, continuation, -1,
            "COMMIT_ALL", List.of()).workflowRevision()).isZero();
    }
}
