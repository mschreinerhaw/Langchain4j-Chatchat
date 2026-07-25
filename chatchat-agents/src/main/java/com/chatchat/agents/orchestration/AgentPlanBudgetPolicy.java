package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the Agent-configured planning budget and applies it as a hard ceiling
 * to model-produced execution policies.
 */
final class AgentPlanBudgetPolicy {

    private AgentPlanBudgetPolicy() {
    }

    static BudgetCaps fromRuntimeAttributes(Map<String, Object> runtimeAttributes) {
        Object rawWorkflow = runtimeAttributes == null ? null : runtimeAttributes.get("mcpWorkflow");
        Map<?, ?> workflow = asMap(rawWorkflow);
        Object nested = firstPresent(workflow.get("mcpWorkflow"), workflow.get("mcp_workflow"));
        if (nested != null && nested != rawWorkflow) {
            Map<?, ?> nestedWorkflow = asMap(nested);
            if (!nestedWorkflow.isEmpty()) {
                workflow = nestedWorkflow;
            }
        }
        Map<?, ?> strategy = asMap(firstPresent(
            workflow.get("executionStrategy"),
            workflow.get("execution_strategy")
        ));
        Integer maxSteps = positiveInteger(firstPresent(strategy.get("maxSteps"), strategy.get("max_steps")));
        Double costBudget = nonNegativeDouble(firstPresent(strategy.get("costBudget"), strategy.get("cost_budget")));
        Integer latencyBudgetMs = positiveInteger(firstPresent(
            strategy.get("latencyBudgetMs"),
            strategy.get("latency_budget_ms")
        ));
        return new BudgetCaps(maxSteps, costBudget, latencyBudgetMs);
    }

    static ApplyResult apply(InterpretationPlan plan, BudgetCaps caps) {
        if (plan == null || caps == null || !caps.configured()) {
            return new ApplyResult(plan, false);
        }
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        if (policy == null) {
            policy = new InterpretationPlan.ExecutionPolicy(
                caps.maxSteps(), false, null, null, null, null, null,
                null, caps.costBudget(), caps.latencyBudgetMs(), null
            );
            return new ApplyResult(withPolicy(plan, policy), true);
        }
        Integer maxSteps = ceiling(policy.maxSteps(), caps.maxSteps());
        Double costBudget = ceiling(policy.costBudget(), caps.costBudget());
        Integer latencyBudgetMs = ceiling(policy.latencyBudgetMs(), caps.latencyBudgetMs());
        if (Objects.equals(maxSteps, policy.maxSteps())
            && Objects.equals(costBudget, policy.costBudget())
            && Objects.equals(latencyBudgetMs, policy.latencyBudgetMs())) {
            return new ApplyResult(plan, false);
        }
        InterpretationPlan.ExecutionPolicy guarded = new InterpretationPlan.ExecutionPolicy(
            maxSteps,
            policy.allowParallel(),
            policy.allowTool(),
            policy.denyTool(),
            policy.timeoutMs(),
            policy.maxRewriteTimes(),
            policy.fallbackMode(),
            policy.toolPriority(),
            costBudget,
            latencyBudgetMs,
            policy.accuracyVsSpeed()
        );
        return new ApplyResult(withPolicy(plan, guarded), true);
    }

    private static InterpretationPlan withPolicy(InterpretationPlan plan,
                                                 InterpretationPlan.ExecutionPolicy policy) {
        return new InterpretationPlan(
            plan.version(),
            plan.intent(),
            plan.context(),
            plan.plan(),
            policy,
            plan.review()
        );
    }

    private static Integer ceiling(Integer selected, Integer configuredMaximum) {
        if (configuredMaximum == null) {
            return selected;
        }
        return selected == null ? configuredMaximum : Math.min(selected, configuredMaximum);
    }

    private static Double ceiling(Double selected, Double configuredMaximum) {
        if (configuredMaximum == null) {
            return selected;
        }
        return selected == null ? configuredMaximum : Math.min(selected, configuredMaximum);
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer positiveInteger(Object value) {
        Long parsed = number(value);
        if (parsed == null || parsed <= 0L) {
            return null;
        }
        return (int) Math.min(Integer.MAX_VALUE, parsed);
    }

    private static Double nonNegativeDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            double parsed = value instanceof Number number
                ? number.doubleValue()
                : Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) && parsed >= 0.0d ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record BudgetCaps(Integer maxSteps, Double costBudget, Integer latencyBudgetMs) {
        boolean configured() {
            return maxSteps != null || costBudget != null || latencyBudgetMs != null;
        }

        Map<String, Object> metadata() {
            Map<String, Object> values = new LinkedHashMap<>();
            if (maxSteps != null) {
                values.put("maxSteps", maxSteps);
            }
            if (costBudget != null) {
                values.put("costBudget", costBudget);
            }
            if (latencyBudgetMs != null) {
                values.put("latencyBudgetMs", latencyBudgetMs);
            }
            return values;
        }
    }

    record ApplyResult(InterpretationPlan plan, boolean adjusted) {
    }
}
