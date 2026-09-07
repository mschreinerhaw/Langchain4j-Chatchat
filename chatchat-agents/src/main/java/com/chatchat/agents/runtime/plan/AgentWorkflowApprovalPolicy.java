package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Projects persisted Agent workflow confirmation choices into plan-time tool approvals. */
public final class AgentWorkflowApprovalPolicy {

    private static final Set<String> AUTO_EXECUTE = Set.of(
        "none", "no", "false", "auto", "auto_execute");

    private AgentWorkflowApprovalPolicy() {
    }

    public static InterpretationPlan apply(InterpretationPlan plan, Object rawWorkflow) {
        if (plan == null || plan.executionPolicy() == null) {
            return plan;
        }
        List<String> approved = approvedTools(rawWorkflow);
        if (approved.isEmpty()) {
            return plan;
        }
        LinkedHashSet<String> allowTools = new LinkedHashSet<>(
            plan.executionPolicy().allowTool() == null ? List.of() : plan.executionPolicy().allowTool());
        plan.steps().stream()
            .filter(step -> step != null && step.mcpToolAction())
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> approved.stream().anyMatch(configured -> sameTool(configured, tool)))
            .forEach(allowTools::add);
        if (allowTools.equals(new LinkedHashSet<>(plan.executionPolicy().allowTool()))) {
            return plan;
        }
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        InterpretationPlan.ExecutionPolicy effectivePolicy = new InterpretationPlan.ExecutionPolicy(
            policy.maxSteps(), policy.allowParallel(), new ArrayList<>(allowTools), policy.denyTool(),
            policy.timeoutMs(), policy.maxRewriteTimes(), policy.fallbackMode(), policy.toolPriority(),
            policy.costBudget(), policy.latencyBudgetMs(), policy.accuracyVsSpeed());
        return new InterpretationPlan(
            plan.version(), plan.intent(), plan.context(), plan.plan(), effectivePolicy, plan.review());
    }

    public static List<String> approvedTools(Object rawWorkflow) {
        Map<String, Object> workflow = workflow(rawWorkflow);
        if (workflow.isEmpty() || Boolean.FALSE.equals(workflow.get("enabled"))) {
            return List.of();
        }
        Object rawSteps = workflow.get("steps");
        if (!(rawSteps instanceof Collection<?> steps)) {
            return List.of();
        }
        LinkedHashSet<String> approved = new LinkedHashSet<>();
        for (Object rawStep : steps) {
            Map<String, Object> step = map(rawStep);
            String confirmation = normalize(step.get("confirmation"));
            if (!AUTO_EXECUTE.contains(confirmation)) {
                continue;
            }
            addTool(approved, first(step, "tool", "toolName"));
            Object parallel = first(step, "parallelSteps", "parallel_steps");
            if (parallel instanceof Collection<?> tools) {
                tools.forEach(tool -> addTool(approved, tool));
            }
        }
        return List.copyOf(approved);
    }

    private static Map<String, Object> workflow(Object rawWorkflow) {
        if (rawWorkflow instanceof Collection<?> steps) {
            Map<String, Object> workflow = new LinkedHashMap<>();
            workflow.put("enabled", true);
            workflow.put("steps", steps);
            return workflow;
        }
        return map(rawWorkflow);
    }

    private static void addTool(Set<String> tools, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            tools.add(String.valueOf(value).trim());
        }
    }

    private static boolean sameTool(String configured, String planned) {
        String left = normalizeTool(configured);
        String right = normalizeTool(planned);
        return !left.isBlank() && (left.equals(right)
            || left.endsWith("_" + right)
            || right.endsWith("_" + left));
    }

    private static String normalizeTool(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }
}
