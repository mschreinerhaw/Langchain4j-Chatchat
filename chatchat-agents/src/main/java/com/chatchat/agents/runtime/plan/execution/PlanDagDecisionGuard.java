package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates model DAG decisions against the deterministic Runtime Ready set. */
public final class PlanDagDecisionGuard {

    public DecisionValidation validate(
        InterpretationPlanRuntime.DagDecision decision,
        InterpretationPlan plan,
        Set<Integer> remaining,
        Map<Integer, InterpretationPlan.Step> stepsById,
        Set<Integer> completedStepIds,
        Set<Integer> readyStepIds
    ) {
        if (decision == null) {
            return DecisionValidation.invalid(
                "DAG_DECISION_FAILED", "LLM DAG controller returned no decision");
        }
        String action = normalize(decision.action());
        if (!InterpretationExecutionProtocol.ACTIONS.contains(action)) {
            return DecisionValidation.invalid(
                "DAG_DECISION_REJECTED", "Unsupported DAG controller action: " + decision.action());
        }
        if ("abort".equals(action) || "rewrite_plan".equals(action)) {
            return DecisionValidation.control(action);
        }
        List<Integer> stepIds = safeIntegers(decision.stepIds()).stream()
            .filter(stepId -> stepId != null)
            .distinct()
            .toList();
        if (stepIds.isEmpty()) {
            return DecisionValidation.invalid(
                "DAG_DECISION_REJECTED", "DAG controller must choose at least one step id");
        }
        if (stepIds.size() > 1 && !allowsParallel(plan)) {
            return DecisionValidation.invalid(
                "DAG_DECISION_REJECTED",
                "DAG controller selected multiple steps but allow_parallel is false");
        }
        if ("execute_step".equals(action) && stepIds.size() > 1) {
            return DecisionValidation.invalid(
                "DAG_DECISION_REJECTED", "execute_step may select only one step");
        }
        List<InterpretationPlan.Step> selected = new ArrayList<>();
        Set<Integer> safeRemaining = remaining == null ? Set.of() : remaining;
        Set<Integer> safeReady = readyStepIds == null ? Set.of() : readyStepIds;
        Set<Integer> safeCompleted = completedStepIds == null ? Set.of() : completedStepIds;
        Map<Integer, InterpretationPlan.Step> safeSteps = stepsById == null ? Map.of() : stepsById;
        for (Integer stepId : stepIds) {
            if (!safeReady.contains(stepId)) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "DAG controller selected step " + stepId
                        + " outside the Runtime Ready set: " + safeReady);
            }
            if (!safeRemaining.contains(stepId)) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "DAG controller selected a step that is not remaining: " + stepId);
            }
            InterpretationPlan.Step step = safeSteps.get(stepId);
            if (step == null) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED", "DAG controller selected unknown step: " + stepId);
            }
            if (!safeCompleted.containsAll(safeIntegers(step.dependsOn()))) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "DAG controller selected step " + stepId
                        + " before dependencies were satisfied: " + safeIntegers(step.dependsOn()));
            }
            if ("final_answer".equals(action) && !step.finalAnswerAction()) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "final_answer action must select a final_answer step");
            }
            selected.add(step);
        }
        boolean selectedFinalAnswer = selected.stream().anyMatch(InterpretationPlan.Step::finalAnswerAction);
        if ("final_answer".equals(action) || selectedFinalAnswer) {
            List<Integer> pendingSteps = safeRemaining.stream()
                .filter(stepId -> !stepIds.contains(stepId))
                .sorted()
                .toList();
            if (!pendingSteps.isEmpty()) {
                return DecisionValidation.invalid(
                    "DAG_DECISION_REJECTED",
                    "final_answer must be the last executed step and cannot skip remaining steps: "
                        + pendingSteps);
            }
        }
        return DecisionValidation.executable(action, selected);
    }

    public Map<String, Object> metadata(DecisionValidation validation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("protocolVersion", InterpretationExecutionProtocol.VERSION);
        metadata.put("allowed", validation != null && validation.valid());
        metadata.put("status", validation != null && validation.valid() ? "accepted" : "rejected");
        metadata.put("reason", validation == null || validation.message() == null
            ? "Runtime guard accepted DAG decision." : validation.message());
        metadata.put("validatedAction", validation == null ? null : validation.action());
        metadata.put("validatedStepIds", validation == null
            ? List.of()
            : validation.steps().stream().map(InterpretationPlan.Step::id).toList());
        return metadata;
    }

    public boolean allowsParallel(InterpretationPlan plan) {
        return plan != null && plan.executionPolicy() != null
            && Boolean.TRUE.equals(plan.executionPolicy().allowParallel());
    }

    private List<Integer> safeIntegers(List<Integer> values) {
        return values == null ? List.of() : values;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record DecisionValidation(
        boolean valid,
        String status,
        String message,
        String action,
        List<InterpretationPlan.Step> steps
    ) {
        public DecisionValidation {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public static DecisionValidation invalid(String status, String message) {
            return new DecisionValidation(false, status, message, null, List.of());
        }

        public static DecisionValidation control(String action) {
            return new DecisionValidation(true, null, null, action, List.of());
        }

        public static DecisionValidation executable(String action, List<InterpretationPlan.Step> steps) {
            return new DecisionValidation(true, null, null, action, steps);
        }
    }
}
