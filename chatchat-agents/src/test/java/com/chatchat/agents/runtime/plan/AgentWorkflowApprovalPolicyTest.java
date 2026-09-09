package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowApprovalPolicyTest {

    @Test
    void appliesApprovedWorkflowToolWhenPlannerOmitsAllowToolList() {
        InterpretationPlan.Step toolStep = new InterpretationPlan.Step(
            1, "mcp_tool", "financial_query", Map.of(), List.of(), null, null);
        InterpretationPlan.ExecutionPolicy policy = new InterpretationPlan.ExecutionPolicy(
            2, false, null, null, 30_000, 0, "safe_answer", null, null, null, null);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "query customer", "low"),
            null,
            new InterpretationPlan.Plan(List.of(toolStep)),
            policy,
            null
        );

        InterpretationPlan applied = AgentWorkflowApprovalPolicy.apply(plan, Map.of(
            "enabled", true,
            "steps", List.of(Map.of("tool", "financial_query", "confirmation", "none"))
        ));

        assertThat(applied.executionPolicy().allowTool()).containsExactly("financial_query");
    }
}
