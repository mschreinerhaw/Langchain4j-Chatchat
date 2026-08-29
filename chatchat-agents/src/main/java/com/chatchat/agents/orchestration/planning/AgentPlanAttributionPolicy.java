package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.runtime.plan.InterpretationPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Selects a scored plan candidate and applies the only deterministic repair
 * allowed at the planning boundary: an unambiguous unavailable-tool mapping.
 */
public final class AgentPlanAttributionPolicy {

    private final int generationLimit;

    public AgentPlanAttributionPolicy(int generationLimit) {
        this.generationLimit = Math.max(1, generationLimit);
    }

    public AgentDecision selectAndAttribute(PlanRewriteContext rewriteContext,
                                            PlannerValidationContext validationContext,
                                            Function<InterpretationPlan, AgentDecision> repairedPlanValidator,
                                            Predicate<String> toolAvailability) {
        if (rewriteContext == null || rewriteContext.candidates().isEmpty()) {
            return null;
        }
        PlanCandidate selected = selectBestCandidate(rewriteContext.candidates());
        if (selected == null || selected.decision() == null) {
            return null;
        }
        RepairResult repair = deterministicGuardRepair(
            selected.decision(), validationContext, repairedPlanValidator, toolAvailability);
        String reason = attributionReason(selected, repair);
        return withAttributionMetadata(
            repair.decision(), selected, rewriteContext, reason, repair.applied(), repair.notes());
    }

    public AgentDecision attribute(AgentDecision decision,
                                   PlanCandidate selected,
                                   PlanRewriteContext context,
                                   String reason,
                                   boolean repairApplied,
                                   List<String> repairNotes) {
        return withAttributionMetadata(
            decision, selected, context, reason, repairApplied, repairNotes);
    }

    private PlanCandidate selectBestCandidate(List<PlanCandidate> candidates) {
        PlanCandidate best = null;
        for (PlanCandidate candidate : candidates == null ? List.<PlanCandidate>of() : candidates) {
            if (candidate == null || candidate.decision() == null) {
                continue;
            }
            if (best == null || betterCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean betterCandidate(PlanCandidate candidate, PlanCandidate currentBest) {
        if (candidate.deterministicScore() != currentBest.deterministicScore()) {
            return candidate.deterministicScore() > currentBest.deterministicScore();
        }
        boolean candidateValid = !plannerPlanInvalid(candidate.decision());
        boolean bestValid = !plannerPlanInvalid(currentBest.decision());
        if (candidateValid != bestValid) {
            return candidateValid;
        }
        int candidateIssueCount = plannerIssues(candidate.decision()).size();
        int bestIssueCount = plannerIssues(currentBest.decision()).size();
        if (candidateIssueCount != bestIssueCount) {
            return candidateIssueCount < bestIssueCount;
        }
        return candidate.attempt() > currentBest.attempt();
    }

    private RepairResult deterministicGuardRepair(AgentDecision decision,
                                                  PlannerValidationContext validationContext,
                                                  Function<InterpretationPlan, AgentDecision> repairedPlanValidator,
                                                  Predicate<String> toolAvailability) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null) {
            return new RepairResult(decision, false, List.of());
        }
        Predicate<String> availability = toolAvailability == null ? ignored -> false : toolAvailability;
        List<String> unavailableTools = plan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .filter(tool -> !availability.test(tool))
            .distinct()
            .toList();
        if (unavailableTools.isEmpty()) {
            return new RepairResult(decision, false, List.of());
        }
        List<String> replacements = candidateReplacementTools(validationContext, availability);
        if (replacements.size() != 1) {
            return new RepairResult(decision, false,
                List.of("Skipped repair because replacement tool was ambiguous."));
        }
        String replacement = replacements.get(0);
        if (!availability.test(replacement)) {
            return new RepairResult(decision, false,
                List.of("Skipped repair because replacement tool is unavailable."));
        }
        Map<String, String> toolReplacements = new LinkedHashMap<>();
        unavailableTools.forEach(tool -> toolReplacements.put(tool, replacement));
        InterpretationPlan repairedPlan = replaceUnavailableTools(plan, toolReplacements);
        AgentDecision repairedDecision = repairedPlanValidator == null
            ? null : repairedPlanValidator.apply(repairedPlan);
        if (repairedDecision == null || plannerPlanInvalid(repairedDecision)) {
            return new RepairResult(decision, false,
                List.of("Skipped repair because repaired plan did not pass validation."));
        }
        List<String> notes = unavailableTools.stream()
            .map(tool -> "Replaced unavailable tool " + tool + " with " + replacement + ".")
            .toList();
        return new RepairResult(repairedDecision, true, notes);
    }

    private List<String> candidateReplacementTools(PlannerValidationContext context,
                                                   Predicate<String> availability) {
        List<String> mandatoryTools = context == null ? List.of() : normalizeList(context.mandatoryTools());
        List<String> availableTools = context == null ? List.of() : normalizeList(context.availableTools());
        List<String> mandatoryAvailable = mandatoryTools.stream().filter(availability).distinct().toList();
        if (mandatoryAvailable.size() == 1) {
            return mandatoryAvailable;
        }
        List<String> registeredAvailable = availableTools.stream().filter(availability).distinct().toList();
        if (registeredAvailable.size() == 1) {
            return registeredAvailable;
        }
        String documentTool = context == null ? null : context.documentSearchTool();
        if (documentTool != null && !documentTool.isBlank() && availability.test(documentTool)) {
            return List.of(documentTool);
        }
        return registeredAvailable;
    }

    private InterpretationPlan replaceUnavailableTools(InterpretationPlan plan,
                                                        Map<String, String> replacements) {
        List<InterpretationPlan.Step> steps = plan.steps().stream()
            .map(step -> replaceStepTool(step, replacements))
            .toList();
        InterpretationPlan.Plan original = plan.plan();
        InterpretationPlan.Plan repaired = new InterpretationPlan.Plan(
            steps,
            original == null ? List.of() : original.edgeContracts(),
            original == null ? List.of() : original.dependencyContracts(),
            original == null ? List.of() : original.bindings(),
            original == null ? null : original.stability(),
            original == null ? null : original.diagnosticProfile(),
            original == null ? List.of() : original.conditionalEdges(),
            original == null ? List.of() : original.branchGroups()
        );
        return new InterpretationPlan(
            plan.version(), plan.intent(), plan.context(), repaired,
            replacePolicyTools(plan.executionPolicy(), replacements), plan.review());
    }

    private InterpretationPlan.Step replaceStepTool(InterpretationPlan.Step step,
                                                    Map<String, String> replacements) {
        if (step == null || !step.mcpToolAction() || !replacements.containsKey(step.toolName())) {
            return step;
        }
        return new InterpretationPlan.Step(
            step.id(), step.actionType(), replacements.get(step.toolName()), step.input(),
            step.dependsOn(), step.outputContract(), step.validation());
    }

    private InterpretationPlan.ExecutionPolicy replacePolicyTools(
        InterpretationPlan.ExecutionPolicy policy,
        Map<String, String> replacements
    ) {
        if (policy == null) {
            return null;
        }
        return new InterpretationPlan.ExecutionPolicy(
            policy.maxSteps(), policy.allowParallel(), replaceToolList(policy.allowTool(), replacements),
            replaceToolList(policy.denyTool(), replacements), policy.timeoutMs(), policy.maxRewriteTimes(),
            policy.fallbackMode(), replaceToolPriority(policy.toolPriority(), replacements),
            policy.costBudget(), policy.latencyBudgetMs(), policy.accuracyVsSpeed());
    }

    private List<String> replaceToolList(List<String> tools, Map<String, String> replacements) {
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
            .map(tool -> replacements.getOrDefault(tool, tool))
            .filter(tool -> tool != null && !tool.isBlank())
            .distinct()
            .toList();
    }

    private Map<String, Double> replaceToolPriority(Map<String, Double> priorities,
                                                    Map<String, String> replacements) {
        if (priorities == null || priorities.isEmpty()) {
            return priorities;
        }
        Map<String, Double> values = new LinkedHashMap<>();
        priorities.forEach((tool, priority) -> values.put(replacements.getOrDefault(tool, tool), priority));
        return values;
    }

    private AgentDecision withAttributionMetadata(AgentDecision decision,
                                                  PlanCandidate selected,
                                                  PlanRewriteContext context,
                                                  String reason,
                                                  boolean repairApplied,
                                                  List<String> repairNotes) {
        if (decision == null) {
            return null;
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(
            decision.executionPlan() == null ? Map.of() : decision.executionPlan());
        executionPlan.put("plannerAttributionSelection", true);
        executionPlan.put("plannerAttributionSource", "deterministic_java");
        executionPlan.put("plannerAttributionContractVersion", "plan_attribution_v1");
        executionPlan.put("plannerGenerationLimit", generationLimit);
        executionPlan.put("plannerGenerationCount", context.candidates().size());
        executionPlan.put("plannerAttributionCandidateCount", context.candidates().size());
        executionPlan.put("plannerAttributionSelected", selected.label());
        executionPlan.put("plannerAttributionSelectedAttempt", selected.attempt());
        executionPlan.put("plannerAttributionReason", reason);
        executionPlan.put("plannerAttributionAnalysis", reason);
        executionPlan.put("plannerAttributionScores", attributionScores(context));
        executionPlan.put("plannerAttributionCandidates", attributionCandidates(context));
        executionPlan.put("plannerAttributionFailurePattern", context.failurePattern());
        executionPlan.put("plannerAttributionCandidateFingerprints", context.candidates().stream()
            .map(candidate -> Map.of(
                "label", candidate.label(), "fingerprint", candidate.fingerprint(),
                "failurePattern", candidate.failurePattern(),
                "deterministicScore", candidate.deterministicScore()))
            .toList());
        executionPlan.put("plannerAttributionRepairApplied", repairApplied);
        executionPlan.put("plannerAttributionRepairNotes", repairNotes == null ? List.of() : repairNotes);
        return new AgentDecision(
            decision.action(), decision.toolName(), decision.arguments(), decision.answer(),
            decision.reason(), executionPlan, decision.sufficient(), decision.interpretationPlan());
    }

    private Map<String, Object> attributionScores(PlanRewriteContext context) {
        Map<String, Object> scores = new LinkedHashMap<>();
        context.candidates().forEach(candidate -> scores.put(candidate.label(), candidate.deterministicScore()));
        return scores;
    }

    private List<Map<String, Object>> attributionCandidates(PlanRewriteContext context) {
        return context.candidates().stream().map(candidate -> {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("label", candidate.label());
            record.put("attempt", candidate.attempt());
            record.put("failurePattern", candidate.failurePattern());
            record.put("fingerprint", candidate.fingerprint());
            record.put("deterministicScore", candidate.deterministicScore());
            record.put("scoreDetails", candidate.deterministicScoreDetails());
            record.put("issues", plannerIssues(candidate.decision()));
            record.put("valid", candidate.decision() != null && !plannerPlanInvalid(candidate.decision()));
            return record;
        }).toList();
    }

    private String attributionReason(PlanCandidate selected, RepairResult repair) {
        String reason = "Selected candidate " + selected.label()
            + " by deterministic attribution score " + selected.deterministicScore() + "/100.";
        return repair.applied()
            ? reason + " Applied guard repair for verifiable unavailable-tool mapping."
            : reason;
    }

    private boolean plannerPlanInvalid(AgentDecision decision) {
        if (decision == null) {
            return true;
        }
        if (decision.interpretationPlan() == null) {
            return !"final".equals(decision.action()) || decision.answer() == null || decision.answer().isBlank();
        }
        Map<String, Object> executionPlan = decision.executionPlan();
        return executionPlan != null
            && (Boolean.FALSE.equals(executionPlan.get("interpretationPlanValid"))
                || Boolean.FALSE.equals(executionPlan.get("interpretationPlanExecutable")));
    }

    private List<String> plannerIssues(AgentDecision decision) {
        if (decision == null || decision.executionPlan() == null) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        addIssues(issues, decision.executionPlan().get("interpretationPlanRuntimeIssues"));
        addIssues(issues, decision.executionPlan().get("interpretationPlanIssues"));
        return issues.stream().filter(issue -> !issue.isBlank()).distinct().toList();
    }

    private void addIssues(List<String> target, Object value) {
        if (value instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(target::add);
        }
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }

    private record RepairResult(AgentDecision decision, boolean applied, List<String> notes) {
    }
}
