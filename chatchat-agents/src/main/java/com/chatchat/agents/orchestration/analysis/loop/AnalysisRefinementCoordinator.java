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
        if (templateDiscoveryContinuationRequired(result)) {
            return "TEMPLATE_DISCOVERY_CONTINUATION_REQUIRED: retryInputChanges="
                + result.metadata().getOrDefault("templateDiscoveryRetryInputChanges", Map.of())
                + "; coverageDecision="
                + result.metadata().getOrDefault("templateCoverageDecision", "NEED_NEXT_PAGE")
                + "; retrievalOutcome="
                + result.metadata().getOrDefault("templateRetrievalOutcome", "PAGE_EXHAUSTED_HAS_MORE");
        }
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

    /** A failed tool is not evidence that rewriting the graph can repair it. */
    public RefinementAdmission admitRefinement(InterpretationPlanRuntime.ExecutionResult result,
        List<InterpretationPlanRuntime.ExecutionResult> attempts,
        List<Map<String, Object>> evidenceHistory, List<String> availableTools, int rewrites) {
        if (result != null && result.approvalRequired()) {
            return new RefinementAdmission(false, false, "authorization_required");
        }
        boolean structural = result != null && "INVALID_PLAN".equals(result.status());
        if (structural) {
            return new RefinementAdmission(rewrites == 0, true,
                rewrites == 0 ? "invalid_plan" : "structural_repair_already_attempted");
        }
        if (templateDiscoveryContinuationRequired(result)) {
            return new RefinementAdmission(true, false, "template_discovery_next_page");
        }
        List<String> attempted = new ArrayList<>();
        for (var attempt : attempts == null ? List.<InterpretationPlanRuntime.ExecutionResult>of() : attempts) {
            if (attempt != null && attempt.steps() != null) {
                attempt.steps().stream().filter(Objects::nonNull)
                    .map(InterpretationPlanRuntime.StepExecution::toolName)
                    .filter(Objects::nonNull).forEach(attempted::add);
            }
        }
        if (result != null && result.steps() != null) {
            result.steps().stream().filter(Objects::nonNull)
                .map(InterpretationPlanRuntime.StepExecution::toolName)
                .filter(Objects::nonNull).forEach(attempted::add);
        }
        boolean newPath = requiredTools(evidenceHistory, availableTools, false).stream()
            .anyMatch(required -> attempted.stream().noneMatch(tool ->
                toolNames.sameToolName(tool, required.toolName())));
        return new RefinementAdmission(newPath, false,
            newPath ? "untried_evidence_tool" : "no_verified_new_retrieval_path");
    }

    public record RefinementAdmission(boolean allowed, boolean structuralRepair, String reason) {}

    public boolean templateDiscoveryContinuationRequired(
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        if (result == null) return false;
        if (result.metadata() != null && Boolean.TRUE.equals(
            result.metadata().get("templateDiscoveryContinuationRequired"))) {
            return true;
        }
        return result.steps() != null && result.steps().stream()
            .filter(Objects::nonNull)
            .map(InterpretationPlanRuntime.StepExecution::metadata)
            .filter(Objects::nonNull)
            .anyMatch(metadata -> Boolean.TRUE.equals(
                metadata.get("templateDiscoveryContinuationRequired")));
    }

    /** Keeps protocol-requested paging inside the global Runtime attempt ceiling. */
    public int templateDiscoveryRewriteLimit(InterpretationPlanRuntime.ExecutionResult result) {
        return templateDiscoveryContinuationRequired(result) ? maximumAttempts - 1 : 0;
    }

    /**
     * Applies the Runtime-issued continuation input deterministically. The model may
     * repair the surrounding DAG, but it cannot change the discovery query, replace
     * cursor paging with an exclusion list, or omit the next cursor.
     */
    public InterpretationPlan enforceTemplateDiscoveryContinuation(
        InterpretationPlan original,
        InterpretationPlan rewritten,
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        if (!templateDiscoveryContinuationRequired(result)
            || original == null || rewritten == null || rewritten.plan() == null) {
            return rewritten;
        }
        Integer stepId = continuationStepId(result);
        Map<String, Object> inputChanges = continuationInputChanges(result);
        if (stepId == null || inputChanges.isEmpty()) return rewritten;
        InterpretationPlan.Step originalStep = original.steps().stream()
            .filter(Objects::nonNull)
            .filter(step -> Objects.equals(step.id(), stepId))
            .findFirst().orElse(null);
        if (originalStep == null || originalStep.input() == null) return rewritten;

        boolean changed = false;
        List<InterpretationPlan.Step> steps = new ArrayList<>();
        for (InterpretationPlan.Step step : rewritten.steps()) {
            if (step == null || !Objects.equals(step.id(), stepId)) {
                steps.add(step);
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>(originalStep.input());
            input.putAll(inputChanges);
            steps.add(new InterpretationPlan.Step(
                step.id(), step.actionType(), originalStep.toolName(), input,
                step.dependsOn(), step.outputContract(), step.validation()));
            changed = true;
        }
        if (!changed) return rewritten;
        InterpretationPlan.Plan plan = rewritten.plan();
        return new InterpretationPlan(
            rewritten.version(), rewritten.intent(), rewritten.context(),
            new InterpretationPlan.Plan(
                List.copyOf(steps), plan.edgeContracts(), plan.dependencyContracts(),
                plan.bindings(), plan.stability(), plan.diagnosticProfile(),
                plan.conditionalEdges(), plan.branchGroups()),
            rewritten.executionPolicy(), rewritten.review());
    }

    public boolean templateDiscoveryContinuationSatisfied(
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        if (!templateDiscoveryContinuationRequired(result)) return true;
        if (plan == null || result == null || result.metadata() == null) return false;
        Integer stepId = continuationStepId(result);
        Map<String, Object> changes = continuationInputChanges(result);
        if (stepId == null || changes.isEmpty()) return false;
        return plan.steps().stream().filter(Objects::nonNull)
            .filter(step -> Objects.equals(step.id(), stepId))
            .anyMatch(step -> changes.entrySet().stream()
                .allMatch(change -> Objects.equals(step.input().get(change.getKey()), change.getValue())));
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

    private Integer continuationStepId(InterpretationPlanRuntime.ExecutionResult result) {
        if (result == null) return null;
        Integer stepId = result.metadata() == null
            ? null : integerValue(result.metadata().get("failedStepId"));
        if (stepId != null) return stepId;
        return result.steps() == null ? null : result.steps().stream()
            .filter(Objects::nonNull)
            .filter(step -> step.metadata() != null && Boolean.TRUE.equals(
                step.metadata().get("templateDiscoveryContinuationRequired")))
            .map(InterpretationPlanRuntime.StepExecution::stepId)
            .filter(Objects::nonNull)
            .findFirst().orElse(null);
    }

    private Map<String, Object> continuationInputChanges(
        InterpretationPlanRuntime.ExecutionResult result
    ) {
        if (result == null) return Map.of();
        Object direct = result.metadata() == null ? null
            : result.metadata().get("templateDiscoveryRetryInputChanges");
        if (direct instanceof Map<?, ?> raw) return asStringMap(raw);
        if (result.steps() == null) return Map.of();
        return result.steps().stream()
            .filter(Objects::nonNull)
            .map(InterpretationPlanRuntime.StepExecution::metadata)
            .filter(Objects::nonNull)
            .map(metadata -> metadata.get("templateDiscoveryRetryInputChanges"))
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(this::asStringMap)
            .findFirst().orElse(Map.of());
    }

    private Map<String, Object> asStringMap(Map<?, ?> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) values.put(String.valueOf(key), value);
        });
        return values;
    }
}
