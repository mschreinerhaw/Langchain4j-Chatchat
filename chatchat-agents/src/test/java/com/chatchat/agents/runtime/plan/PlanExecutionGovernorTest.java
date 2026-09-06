package com.chatchat.agents.runtime.plan;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlanExecutionGovernorTest {
    @Test void slowCompletedReviewDoesNotPreventRemainingToolsFromRunning() {
        var policy = new InterpretationPlan.ExecutionPolicy(4, false, null, null, null,
            1, null, null, null, 1, null);
        var plan = new InterpretationPlan("1.0", null, null, null, policy, null);
        assertThat(new PlanExecutionGovernor().check(plan, System.currentTimeMillis() - 300_000,
            2, Map.of())).isEmpty();
    }

    @Test void costLimitRemainsEnforcedIndependentlyOfModelWaitingTime() {
        var policy = new InterpretationPlan.ExecutionPolicy(4, false, null, null, null,
            1, null, null, 1D, 1, null);
        var plan = new InterpretationPlan("1.0", null, null, null, policy, null);
        assertThat(new PlanExecutionGovernor().check(plan, System.currentTimeMillis() - 300_000,
            2, Map.of("__agentEstimatedCost", 2D))).get()
            .extracting(PlanExecutionGovernor.Violation::code).isEqualTo("PLAN_COST_BUDGET_EXCEEDED");
    }
}
