package com.chatchat.agents.runtime.plan;

import java.util.Map;
import java.util.Optional;

/** Central fail-closed budget boundary for the DAG executor. */
public final class PlanExecutionGovernor {

    public Optional<Violation> check(InterpretationPlan plan,
                                     long startedAt,
                                     int executedSteps,
                                     Map<String, Object> runtimeAttributes) {
        if (plan == null || plan.executionPolicy() == null) return Optional.empty();
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        if (policy.latencyBudgetMs() != null && policy.latencyBudgetMs() > 0) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
            if (elapsed >= policy.latencyBudgetMs()) {
                return Optional.of(new Violation("PLAN_LATENCY_BUDGET_EXCEEDED",
                    "InterpretationPlan latency budget exhausted", Map.of(
                        "elapsedMs", elapsed,
                        "latencyBudgetMs", policy.latencyBudgetMs(),
                        "executedSteps", executedSteps)));
            }
        }
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
