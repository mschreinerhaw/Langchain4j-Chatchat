package com.chatchat.agents.orchestration.planning.evolution;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;

import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/**
 * Audits bounded DAG repair and plan evolution as durable runtime observations.
 * It does not execute or rewrite plans.
 */
public final class AgentPlanEvolutionAuditor {

    private final AgentRunResultAdapter runResultAdapter;
    private final String agentRunIdAttribute;

    public AgentPlanEvolutionAuditor(AgentRunResultAdapter runResultAdapter, String agentRunIdAttribute) {
        this.runResultAdapter = Objects.requireNonNull(runResultAdapter, "runResultAdapter");
        this.agentRunIdAttribute = Objects.requireNonNull(agentRunIdAttribute, "agentRunIdAttribute");
    }

    public void recordDagRepair(Map<String, Object> runtimeAttributes,
                                      Map<String, Object> metadata,
                                      String eventState,
                                      int rewriteCount,
                                      String reason,
                                      InterpretationPlan.Step failedStep,
                                      List<Map<String, Object>> changes,
                                      InterpretationPlanValidator.ValidationResult validation) {
        String normalizedState = firstNonBlank(eventState, "UNKNOWN").toUpperCase(Locale.ROOT);
        List<Map<String, Object>> safeChanges = changes == null ? List.of() : List.copyOf(changes);
        List<InterpretationPlanValidator.ValidationIssue> validationIssues = validation == null
            ? List.of()
            : validation.issues();
        Map<String, Object> repairEvent = metadataOf(
            "contractVersion", dagGovernanceContractVersion(runtimeAttributes),
            "eventKind", "DAG_REPAIR",
            "eventState", normalizedState,
            "repairAttempt", rewriteCount,
            "fromIteration", rewriteCount,
            "toIteration", rewriteCount + 1,
            "reason", firstNonBlank(reason, "Runtime detected an evidence or execution gap."),
            "failedStepId", failedStep == null ? null : failedStep.id(),
            "failedToolName", failedStep == null ? null : failedStep.toolName(),
            "changeCount", safeChanges.size(),
            "changes", safeChanges,
            "evidenceContext", metadata == null
                ? Map.of()
                : metadata.getOrDefault("latestDagRepairEvidenceContext", Map.of()),
            "validationIssues", validationIssues,
            "createdAt", System.currentTimeMillis()
        );
        if (metadata != null) {
            metadataList(metadata, "dagRepairEvents").add(repairEvent);
        }
        String content = switch (normalizedState) {
            case "STARTED" -> "Runtime started DAG repair attempt " + rewriteCount
                + " after detecting a recoverable plan or execution failure.";
            case "APPLIED" -> "Runtime applied DAG repair attempt " + rewriteCount
                + " with " + safeChanges.size() + " audited change(s); validation passed.";
            case "REJECTED" -> "DAG repair attempt " + rewriteCount
                + " did not pass runtime validation; the next bounded recovery action will be evaluated.";
            default -> "Runtime recorded DAG repair attempt " + rewriteCount + ".";
        };
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            agentRunIdAttribute,
            content,
            "interpretation_plan_repair",
            metadataOf(
                "type", "repair",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "dag_repair",
                "eventKind", "DAG_REPAIR",
                "eventState", normalizedState,
                "repairAttempt", rewriteCount,
                "repairEvent", repairEvent
            )
        );
    }

    public void recordPlannerRepair(Map<String, Object> runtimeAttributes,
                                             Map<String, Object> metadata,
                                             Object rawRepairEvent) {
        Map<String, Object> repairEvent = asMap(rawRepairEvent);
        if (!"DAG_REPAIR".equalsIgnoreCase(stringValue(repairEvent.get("eventKind")))
            || repairEvent.isEmpty()) {
            return;
        }
        if (metadata != null) {
            metadataList(metadata, "dagRepairEvents").add(new LinkedHashMap<>(repairEvent));
        }
        String repairCode = firstNonBlank(
            stringValue(repairEvent.get("repairCode")), "AUTHORITATIVE_DAG_REPAIR");
        String eventState = firstNonBlank(
            stringValue(repairEvent.get("eventState")), "APPLIED");
        boolean applied = "APPLIED".equalsIgnoreCase(eventState);
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            agentRunIdAttribute,
            applied
                ? "Runtime restored and validated the planner DAG from the authoritative workflow contract ("
                    + repairCode + ")."
                : "Runtime restored the authoritative workflow topology, but the planner candidate remained invalid ("
                    + repairCode + "); deterministic Java scheduling will evaluate the pending Ready tools.",
            "interpretation_plan_repair",
            metadataOf(
                "type", "repair",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "dag_repair",
                "eventKind", "DAG_REPAIR",
                "eventState", eventState,
                "repairEvent", repairEvent
            )
        );
    }

    public void recordEvolution(
        InterpretationPlan previousPlan,
        InterpretationPlan nextPlan,
        int iteration,
        String status,
        List<Map<String, Object>> evidenceHistory,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        if (nextPlan == null) {
            return;
        }
        Map<String, Object> trigger = evidenceHistory == null || evidenceHistory.isEmpty()
            ? Map.of()
            : evidenceHistory.get(evidenceHistory.size() - 1);
        List<Map<String, Object>> changes = changes(previousPlan, nextPlan);
        Map<String, Object> evolution = new LinkedHashMap<>();
        evolution.put("contractVersion", "plan_evolution_v1");
        evolution.put("evolutionId", firstNonBlank(
            stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(agentRunIdAttribute)),
            "agent-run"
        ) + ":plan-evolution:" + iteration);
        evolution.put("fromIteration", Math.max(1, iteration - 1));
        evolution.put("toIteration", iteration);
        evolution.put("status", firstNonBlank(status, "UNKNOWN"));
        evolution.put("trigger", metadataOf(
            "conclusion", trigger.get("conclusion"),
            "missingEvidence", trigger.getOrDefault("missingEvidence", List.of()),
            "conflicts", trigger.getOrDefault("conflicts", List.of()),
            "nextActions", trigger.getOrDefault("nextActions", List.of()),
            "evidenceIds", evidenceIdsFromSnapshot(trigger),
            "hypothesisIds", hypothesisIdsFromSnapshot(trigger),
            "evidenceRelationIds", evidenceRelationIdsFromSnapshot(trigger)
        ));
        evolution.put("changes", changes);
        evolution.put("changed", !changes.isEmpty());
        evolution.put("previousPlan", previousPlan);
        evolution.put("nextPlan", nextPlan);
        evolution.put("createdAt", System.currentTimeMillis());
        addCandidateList(metadataList(metadata, "interpretationPlanEvolutionHistory"), List.of(evolution));
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            agentRunIdAttribute,
            "Plan evolution " + Math.max(1, iteration - 1) + " -> " + iteration
                + " recorded with " + changes.size() + " change(s).",
            "interpretation_plan_evolution",
            metadataOf(
                "type", "plan",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "plan_revision",
                "contractVersion", "plan_evolution_v1",
                "iteration", iteration,
                "planEvolution", evolution
            )
        );
    }

    public List<Map<String, Object>> changes(InterpretationPlan previousPlan, InterpretationPlan nextPlan) {
        Map<Integer, InterpretationPlan.Step> previous = planStepsById(previousPlan);
        Map<Integer, InterpretationPlan.Step> next = planStepsById(nextPlan);
        LinkedHashSet<Integer> stepIds = new LinkedHashSet<>(previous.keySet());
        stepIds.addAll(next.keySet());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Integer stepId : stepIds) {
            InterpretationPlan.Step before = previous.get(stepId);
            InterpretationPlan.Step after = next.get(stepId);
            if (before == null) {
                changes.add(metadataOf("changeType", "STEP_ADDED", "stepId", stepId, "after", after));
            } else if (after == null) {
                changes.add(metadataOf("changeType", "STEP_REMOVED", "stepId", stepId, "before", before));
            } else if (!Objects.equals(before, after)) {
                List<String> fields = new ArrayList<>();
                if (!Objects.equals(before.actionType(), after.actionType())) fields.add("actionType");
                if (!Objects.equals(before.toolName(), after.toolName())) fields.add("toolName");
                if (!Objects.equals(before.input(), after.input())) fields.add("input");
                if (!Objects.equals(before.dependsOn(), after.dependsOn())) fields.add("dependsOn");
                if (!Objects.equals(before.outputContract(), after.outputContract())) fields.add("outputContract");
                if (!Objects.equals(before.validation(), after.validation())) fields.add("validation");
                changes.add(metadataOf(
                    "changeType", "STEP_MODIFIED",
                    "stepId", stepId,
                    "fields", fields,
                    "before", before,
                    "after", after
                ));
            }
        }
        if (previousPlan != null && !Objects.equals(previousPlan.executionPolicy(), nextPlan.executionPolicy())) {
            changes.add(metadataOf(
                "changeType", "EXECUTION_POLICY_MODIFIED",
                "before", previousPlan.executionPolicy(),
                "after", nextPlan.executionPolicy()
            ));
        }
        if (previousPlan != null && !Objects.equals(previousPlan.intent(), nextPlan.intent())) {
            changes.add(metadataOf(
                "changeType", "INTENT_MODIFIED",
                "before", previousPlan.intent(),
                "after", nextPlan.intent()
            ));
        }
        return List.copyOf(changes);
    }

    private Map<Integer, InterpretationPlan.Step> planStepsById(InterpretationPlan plan) {
        Map<Integer, InterpretationPlan.Step> steps = new LinkedHashMap<>();
        if (plan != null && plan.steps() != null) {
            for (InterpretationPlan.Step step : plan.steps()) {
                if (step != null && step.id() != null) {
                    steps.put(step.id(), step);
                }
            }
        }
        return steps;
    }

    private List<String> evidenceIdsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("toolEvidence") instanceof Iterable<?> evidence)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : evidence) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(asStringObjectMap(map).get("evidenceId"));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private List<String> hypothesisIdsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("hypotheses") instanceof Iterable<?> hypotheses)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : hypotheses) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(asStringObjectMap(map).get("hypothesisId"));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private List<String> evidenceRelationIdsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        Map<String, Object> graph = asMap(snapshot.get("evidenceGraph"));
        if (!(graph.get("relations") instanceof Iterable<?> relations)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : relations) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(asStringObjectMap(map).get("relationId"));
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    public String rewriteSummary(
        int nextAttempt,
        InterpretationPlan previousPlan,
        InterpretationPlanRuntime.ExecutionResult previousResult
    ) {
        return rewriteSummary(nextAttempt, previousPlan, previousResult, List.of());
    }

    public String rewriteSummary(
        int nextAttempt,
        InterpretationPlan previousPlan,
        InterpretationPlanRuntime.ExecutionResult previousResult,
        List<Map<String, Object>> evidenceHistory
    ) {
        List<String> failedSteps = new ArrayList<>();
        if (previousResult != null && previousResult.steps() != null) {
            for (InterpretationPlanRuntime.StepExecution step : previousResult.steps()) {
                if (step != null && !step.success()) {
                    failedSteps.add("step " + step.stepId()
                        + " (" + firstNonBlank(step.toolName(), firstNonBlank(step.actionType(), "unknown")) + "): "
                        + firstNonBlank(step.errorMessage(), "result did not satisfy review"));
                }
            }
        }
        String goal = previousPlan == null || previousPlan.intent() == null
            ? null
            : previousPlan.intent().goal();
        return "Plan attempt " + nextAttempt + " rewrite context summary: previous goal="
            + firstNonBlank(goal, "unspecified")
            + "; previous status=" + (previousResult == null ? "unknown" : previousResult.status())
            + "; previous error=" + firstNonBlank(
                previousResult == null ? null : previousResult.errorMessage(),
                "none"
            )
            + "; failed steps=" + failedSteps
            + ". Generate a new complete plan from this evidence, evaluate it, then execute it only if evaluation passes."
            + " Repair evidence context=" + stringify(repairContext(evidenceHistory));
    }

    public Map<String, Object> repairContext(List<Map<String, Object>> evidenceHistory) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> latest = evidenceHistory == null || evidenceHistory.isEmpty()
            ? Map.of()
            : evidenceHistory.get(evidenceHistory.size() - 1);
        context.put("contractVersion", "dag_repair_evidence_context_v1");
        context.put("evidenceIds", evidenceIdsFromSnapshot(latest));
        context.put("evidenceRelationIds", evidenceRelationIdsFromSnapshot(latest));
        context.put("hypothesisIds", hypothesisIdsFromSnapshot(latest));
        context.put("conclusion", latest.getOrDefault("conclusion", ""));
        context.put("missingEvidence", latest.getOrDefault("missingEvidence", List.of()));
        context.put("analysisCoverage", latest.getOrDefault("analysisCoverage", Map.of()));
        context.put("gapRequests", latest.getOrDefault("gapRequests", List.of()));
        context.put("gapFingerprint", latest.getOrDefault("gapFingerprint", ""));
        context.put("conflicts", latest.getOrDefault("conflicts", List.of()));
        context.put("nextActions", latest.getOrDefault("nextActions", List.of()));
        context.put("evidenceQuality", latest.getOrDefault("evidenceQuality", latest.getOrDefault("confidence", null)));
        context.put("sourceState", latest.getOrDefault("sourceState", latest.getOrDefault("overallStatus", "UNKNOWN")));
        return context;
    }

    public String fallbackMode(InterpretationPlan plan) {
        String configured = plan == null || plan.executionPolicy() == null
            ? null
            : plan.executionPolicy().fallbackMode();
        if ("partial_result".equals(configured) || "safe_answer".equals(configured)) {
            return configured;
        }
        return "safe_answer";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> metadataList(Map<String, Object> metadata, String key) {
        Object existing = metadata.get(key);
        if (existing instanceof List<?> list) return (List<Map<String, Object>>) list;
        List<Map<String, Object>> values = new ArrayList<>();
        metadata.put(key, values);
        return values;
    }

    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? asStringObjectMap(map) : Map.of();
    }

    private Map<String, Object> asStringObjectMap(Map<?, ?> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (key != null) values.put(String.valueOf(key), value); });
        return values;
    }

    private String dagGovernanceContractVersion(Map<String, Object> runtimeAttributes) {
        Object raw = runtimeAttributes == null ? null
            : runtimeAttributes.get(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE);
        Object version = raw instanceof Map<?, ?> contract ? contract.get("contractVersion") : null;
        return version == null || String.valueOf(version).isBlank()
            ? DagGovernanceContractProvider.INITIAL_VERSION
            : String.valueOf(version);
    }
}
