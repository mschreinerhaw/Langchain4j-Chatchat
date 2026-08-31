package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Applies workflow and result-review barriers to one completed plan attempt. */
public final class PlanExecutionResultCoordinator {

    private final InterpretationPlanWorkflowGuard workflowGuard =
        new InterpretationPlanWorkflowGuard();

    public Outcome consume(Request request) {
        InterpretationPlanRuntime.ExecutionResult result = request.result();
        if (result == null || result.approvalRequired()) return new Outcome(result, false, List.of());
        InterpretationPlanWorkflowGuard.GuardResult guard = workflowGuard.evaluate(
            new InterpretationPlanWorkflowGuard.GuardContext(request.plan(), result,
                request.mandatoryTools(), request.completedWorkflowTools()));
        boolean workflowBlocked = result.success() && !guard.allowed();
        if (workflowBlocked) result = blockWorkflow(request.stage(), result, guard,
            request.observations(), request.metadata());
        result = review(request.stage(), result, request.observations(), request.metadata());
        return new Outcome(result, workflowBlocked, guard == null ? List.of() : guard.missingRequiredTools());
    }

    public InterpretationPlanRuntime.ExecutionResult review(String stage,
        InterpretationPlanRuntime.ExecutionResult result, List<String> observations,
        Map<String, Object> metadata) {
        if (result == null || !result.success() || result.steps() == null) return result;
        List<String> reasons = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            Map<String, Object> stepMetadata = step == null || step.metadata() == null
                ? Map.of() : step.metadata();
            boolean partialAccepted = Boolean.TRUE.equals(stepMetadata.get("toolResultReviewPartialAccepted"));
            if (!partialAccepted && Boolean.FALSE.equals(stepMetadata.get("toolResultReviewSatisfied"))) {
                reasons.add("step " + step.stepId() + ": " + firstNonBlank(
                    stringValue(stepMetadata.get("toolResultReviewReason")), firstNonBlank(
                        stringValue(stepMetadata.get("toolResultReviewPartialReason")),
                        "result review was not satisfied")));
            }
        }
        if (reasons.isEmpty()) {
            if (metadata != null) metadata.put("interpretationPlanResultSatisfied", true);
            return result;
        }
        String reason = "Plan attempt did not satisfy result review: " + String.join("; ", reasons);
        if (observations != null) observations.add("InterpretationPlan " + stage
            + " requires a full plan rewrite. " + reason);
        if (metadata != null) {
            metadata.put("interpretationPlanResultSatisfied", false);
            metadata.put("interpretationPlanUnsatisfiedStage", stage);
            metadata.put("interpretationPlanUnsatisfiedReasons", reasons);
        }
        Map<String, Object> resultMetadata = new LinkedHashMap<>(
            result.metadata() == null ? Map.of() : result.metadata());
        resultMetadata.put("planResultSatisfied", false);
        resultMetadata.put("planResultUnsatisfiedReasons", reasons);
        return new InterpretationPlanRuntime.ExecutionResult("result_unsatisfied", false, false,
            reason, result.finalAnswer(), result.steps(), resultMetadata, result.durationMs());
    }

    private InterpretationPlanRuntime.ExecutionResult blockWorkflow(String stage,
        InterpretationPlanRuntime.ExecutionResult result,
        InterpretationPlanWorkflowGuard.GuardResult guard,
        List<String> observations, Map<String, Object> metadata) {
        Map<String, Object> guardMetadata = guard == null || guard.metadata() == null
            ? Map.of() : guard.metadata();
        if (metadata != null) {
            metadata.put("interpretationPlanWorkflowBlocked", true);
            metadata.put("interpretationPlanWorkflowBlockedStage", stage);
            metadata.put("interpretationPlanWorkflowGuard", guardMetadata);
            metadata.put("interpretationPlanWorkflowMissingTools",
                guard == null ? List.of() : guard.missingRequiredTools());
            metadata.put("interpretationPlanWorkflowMissingPlanStepIds",
                guard == null ? List.of() : guard.missingPlanStepIds());
        }
        if (observations != null) observations.add(
            "InterpretationPlan final answer blocked: configured MCP workflow must complete before final answer. Missing tools: "
                + (guard == null ? List.of() : guard.missingRequiredTools()) + ", missing plan steps: "
                + (guard == null ? List.of() : guard.missingPlanStepIds()));
        Map<String, Object> resultMetadata = new LinkedHashMap<>(
            result.metadata() == null ? Map.of() : result.metadata());
        resultMetadata.put("workflowGuard", guardMetadata);
        return new InterpretationPlanRuntime.ExecutionResult("MCP_WORKFLOW_INCOMPLETE", false, false,
            guard == null || guard.reason() == null || guard.reason().isBlank()
                ? "Configured MCP workflow is incomplete" : guard.reason(),
            result.finalAnswer(), result.steps(), resultMetadata, result.durationMs());
    }

    public record Request(String stage, InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result, List<String> mandatoryTools,
        List<String> completedWorkflowTools, List<String> observations,
        Map<String, Object> metadata) {
        public Request {
            mandatoryTools = mandatoryTools == null ? List.of() : List.copyOf(mandatoryTools);
            completedWorkflowTools = completedWorkflowTools == null
                ? List.of() : List.copyOf(completedWorkflowTools);
        }
    }

    public record Outcome(InterpretationPlanRuntime.ExecutionResult result,
                          boolean workflowBlocked, List<String> missingRequiredTools) {}
}
