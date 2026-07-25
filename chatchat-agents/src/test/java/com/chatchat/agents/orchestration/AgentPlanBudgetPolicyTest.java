package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlanBudgetPolicyTest {

    @Test
    void resolvesAgentBudgetCapsFromWorkflowExecutionStrategy() {
        AgentPlanBudgetPolicy.BudgetCaps caps = AgentPlanBudgetPolicy.fromRuntimeAttributes(Map.of(
            "mcpWorkflow", Map.of(
                "executionStrategy", Map.of(
                    "maxSteps", 3,
                    "costBudget", 8.5,
                    "latencyBudgetMs", 90000
                )
            )
        ));

        assertThat(caps.maxSteps()).isEqualTo(3);
        assertThat(caps.costBudget()).isEqualTo(8.5);
        assertThat(caps.latencyBudgetMs()).isEqualTo(90000);
    }

    @Test
    void clampsModelSelectedBudgetsToAgentConfiguredCeilings() {
        InterpretationPlan plan = plan(new InterpretationPlan.ExecutionPolicy(
            9, false, List.of(), List.of(), null, 2, "partial_result",
            Map.of(), 25.0, 240000, 0.7
        ));

        AgentPlanBudgetPolicy.ApplyResult result = AgentPlanBudgetPolicy.apply(
            plan,
            new AgentPlanBudgetPolicy.BudgetCaps(3, 10.0, 120000)
        );

        assertThat(result.adjusted()).isTrue();
        assertThat(result.plan().executionPolicy().maxSteps()).isEqualTo(3);
        assertThat(result.plan().executionPolicy().costBudget()).isEqualTo(10.0);
        assertThat(result.plan().executionPolicy().latencyBudgetMs()).isEqualTo(120000);
    }

    @Test
    void preservesSmallerModelSelectedBudgets() {
        InterpretationPlan plan = plan(new InterpretationPlan.ExecutionPolicy(
            2, false, List.of(), List.of(), null, 1, "safe_answer",
            Map.of(), 4.0, 45000, 0.8
        ));

        AgentPlanBudgetPolicy.ApplyResult result = AgentPlanBudgetPolicy.apply(
            plan,
            new AgentPlanBudgetPolicy.BudgetCaps(3, 10.0, 120000)
        );

        assertThat(result.adjusted()).isFalse();
        assertThat(result.plan()).isSameAs(plan);
    }

    @Test
    void fillsMissingModelBudgetsWithAgentCeilings() {
        InterpretationPlan plan = plan(new InterpretationPlan.ExecutionPolicy(
            null, false, List.of(), List.of(), null, null, null
        ));

        AgentPlanBudgetPolicy.ApplyResult result = AgentPlanBudgetPolicy.apply(
            plan,
            new AgentPlanBudgetPolicy.BudgetCaps(3, 10.0, 120000)
        );

        assertThat(result.adjusted()).isTrue();
        assertThat(result.plan().executionPolicy().maxSteps()).isEqualTo(3);
        assertThat(result.plan().executionPolicy().costBudget()).isEqualTo(10.0);
        assertThat(result.plan().executionPolicy().latencyBudgetMs()).isEqualTo(120000);
    }

    private InterpretationPlan plan(InterpretationPlan.ExecutionPolicy policy) {
        return new InterpretationPlan(
            "1.0",
            null,
            null,
            new InterpretationPlan.Plan(List.of()),
            policy,
            null
        );
    }
}
