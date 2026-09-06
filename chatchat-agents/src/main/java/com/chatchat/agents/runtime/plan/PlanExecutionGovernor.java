package com.chatchat.agents.runtime.plan;

import java.util.Map;
import java.util.Optional;

/** Enforces execution cost; planner latency estimates do not cancel required model work. */
public final class PlanExecutionGovernor {

    public Optional<Violation> check(InterpretationPlan plan,
                                     long startedAt,
                                     int executedSteps,
                                     Map<String, Object> runtimeAttributes) {
        if (plan == null || plan.executionPolicy() == null) return Optional.empty();
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        // latencyBudgetMs is a planner estimate, not an authoritative request deadline.
        // Tool-result review can exceed it while still returning valid evidence. Continue
        // ready nodes; explicit cancellation and request deadlines belong to RuntimeGuard.
        if (policy.costBudget() != null && policy.costBudget() >= 0 && runtimeAttributes != null) {
            double cost = number(runtimeAttributes.get("__agentEstimatedCost"));
            if (cost > policy.costBudget()) {
                return Optional.of(new Violation("PLAN_COST_BUDGET_EXCEEDED",
                    "InterpretationPlan cost budget exhausted", Map.of(
                        "estimatedCost", cost,
                        "costBudget", policy.costBudget(),
                        "executedSteps", executedSteps)));
            }
        }
        return Optional.empty();
    }

    private double number(Object value) {
        if (value instanceof Number number) return Math.max(0D, number.doubleValue());
        if (value == null) return 0D;
        try {
            return Math.max(0D, Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }

    public record Violation(String code, String message, Map<String, Object> metadata) {
    }
}
