package com.chatchat.agents.orchestration.analysis.loop;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRewriter;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Owns bounded evidence-refinement routing and reusable execution-state projection. */
public final class AnalysisRefinementCoordinator {

    private final AgentToolNameResolver toolNames;
    private final int maximumAttempts;

    public AnalysisRefinementCoordinator(AgentToolNameResolver toolNames, int maximumAttempts) {
        this.toolNames = toolNames;
        this.maximumAttempts = Math.max(1, maximumAttempts);
    }

    public String rewriteReason(InterpretationPlanRuntime.ExecutionResult result,
                                List<Map<String, Object>> evidenceHistory) {
        Map<String, Object> latest = evidenceHistory == null || evidenceHistory.isEmpty()
            ? Map.of() : evidenceHistory.get(evidenceHistory.size() - 1);
        return "EVIDENCE_REFINEMENT_REQUIRED: conclusion="
            + firstNonBlank(stringValue(latest.get("conclusion")), "none")
            + "; missingEvidence=" + latest.getOrDefault("missingEvidence", List.of())
            + "; analysisCoverage=" + latest.getOrDefault("analysisCoverage", Map.of())
            + "; gapRequests=" + latest.getOrDefault("gapRequests", List.of())
            + "; conflicts=" + latest.getOrDefault("conflicts", List.of())
            + "; previousExecutionError="
            + firstNonBlank(result == null ? null : result.errorMessage(), "none");
    }

    public List<InterpretationPlanRewriter.RequiredToolExecution> requiredTools(
        List<Map<String, Object>> evidenceHistory,
        List<String> availableTools,
        boolean evidenceSufficient
    ) {
        if (evidenceHistory == null || evidenceHistory.isEmpty() || evidenceSufficient) {
            return List.of();
        }
        Object nextActions = evidenceHistory.get(evidenceHistory.size() - 1).get("nextActions");
        if (!(nextActions instanceof Iterable<?> actions)) return List.of();
        List<InterpretationPlanRewriter.RequiredToolExecution> required = new ArrayList<>();
        for (Object action : actions) {
            if (!(action instanceof Map<?, ?> actionMap)) continue;
            String requested = stringValue(firstObject(asStringMap(actionMap),
                "tool", "toolName", "tool_name"));
            String available = matchingAvailableTool(requested, availableTools);
            if (available == null || required.stream().anyMatch(item ->
                toolNames.sameToolName(item.toolName(), available))) continue;
            required.add(new InterpretationPlanRewriter.RequiredToolExecution(
                available, "EVIDENCE_REFINEMENT", true));
        }
        return List.copyOf(required);
    }

    public int evidenceDrivenRewriteLimit(int configured,
        EvidenceAugmentationPolicy.Outcome outcome, boolean refinementAvailable) {
        int bounded = boundedRewriteCount(configured);
        if (bounded == 0 || outcome == null || !outcome.continueLoop()) return 0;
        return refinementAvailable ? maximumAttempts - 1 : bounded;
    }

    public int initialRewriteLimit(int configured, EvidenceAugmentationPolicy.Outcome outcome,
        boolean augmentationOverrideAvailable, boolean executionRecoveryRequired,
        boolean templateExecutionRetryRequested, boolean toolsAvailable) {
        if (outcome == null || !outcome.continueLoop()) return 0;
        int bounded = boundedRewriteCount(configured);
        int limit = augmentationOverrideAvailable ? 1 : bounded;
        if (executionRecoveryRequired) limit = Math.max(limit, bounded);
        if (templateExecutionRetryRequested && toolsAvailable) limit = Math.max(limit, 1);
        return Math.min(maximumAttempts - 1, limit);
    }

    public InterpretationPlan.Step repairRootStep(InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result) {
        InterpretationPlan.Step failed = failedStep(plan, result);
        if (failed != null || plan == null || result == null || result.metadata() == null) return failed;
        Integer rootId = integerValue(result.metadata().get("failedStepId"));
        if (rootId == null) {
            List<Integer> remaining = integerList(result.metadata().get("remainingStepIds"));
            rootId = remaining.isEmpty() ? null : remaining.get(0);
        }
        if (rootId == null) return null;
        Integer selected = rootId;
        return plan.steps().stream().filter(Objects::nonNull)
            .filter(step -> Objects.equals(step.id(), selected)).findFirst().orElse(null);
    }

    public Map<Integer, InterpretationPlanRuntime.ReusableStep> reusableSteps(
        Map<Integer, InterpretationPlanRuntime.ReusableStep> existing,
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        Map<Integer, InterpretationPlanRuntime.ReusableStep> reusable = new LinkedHashMap<>(
            existing == null ? Map.of() : existing);
        if (plan == null || result == null || result.steps() == null) return reusable;
        Map<Integer, InterpretationPlan.Step> definitions = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step != null && step.id() != null) definitions.putIfAbsent(step.id(), step);
        }
        for (InterpretationPlanRuntime.StepExecution execution : result.steps()) {
            InterpretationPlan.Step definition = execution == null ? null : definitions.get(execution.stepId());
            if (definition != null && execution.success()) {
                reusable.put(definition.id(), new InterpretationPlanRuntime.ReusableStep(definition, execution));
            }
        }
        return reusable;
    }

    private InterpretationPlan.Step failedStep(InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result) {
        if (plan == null || result == null || result.steps() == null) return null;
        Integer failedId = result.steps().stream().filter(step -> !step.success())
            .map(InterpretationPlanRuntime.StepExecution::stepId).findFirst().orElse(null);
        if (failedId == null) return null;
        return plan.steps().stream().filter(step -> failedId.equals(step.id())).findFirst().orElse(null);
    }

    private String matchingAvailableTool(String requested, List<String> availableTools) {
        if (requested == null || requested.isBlank() || availableTools == null) return null;
        String specific = toolNames.resolveMostSpecificAvailableTool(requested, availableTools);
        if (specific != null) return specific;
        if (toolNames.isAbstractCapability(requested)) return null;
        return availableTools.stream().filter(tool -> toolNames.sameToolName(requested, tool))
            .findFirst().orElse(null);
    }

    private int boundedRewriteCount(int configured) {
        return Math.max(0, Math.min(maximumAttempts - 1, configured));
    }

    private Map<String, Object> asStringMap(Map<?, ?> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) values.put(String.valueOf(key), value);
        });
        return values;
    }
}
