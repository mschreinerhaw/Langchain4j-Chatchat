package com.chatchat.agents.orchestration.evidence;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;

import com.chatchat.agents.orchestration.evidence.AgentEvidenceGraphService;
import com.chatchat.agents.orchestration.evidence.AgentToolResultFactExtractor;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.plan.diagnostic.DiagnosticRun;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.runtime.summary.analysis.contract.AnalysisLoopContract;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/**
 * Converts completed InterpretationPlan steps into governed evidence objects,
 * quality dimensions, hypotheses and an evidence graph. It performs no
 * scheduling, plan rewriting or final-answer generation.
 */
public final class InterpretationPlanEvidenceAnalyzer {

    private final AgentToolResultFactExtractor factExtractor;
    private final AgentEvidenceGraphService evidenceGraphService;
    private final AgentToolNameResolver toolNames;
    private final AgentRunResultAdapter runResultAdapter;
    private final String agentRunIdAttribute;

    public InterpretationPlanEvidenceAnalyzer(AgentToolResultFactExtractor factExtractor,
                                       AgentEvidenceGraphService evidenceGraphService,
                                       AgentToolNameResolver toolNames,
                                       AgentRunResultAdapter runResultAdapter,
                                       String agentRunIdAttribute) {
        this.factExtractor = Objects.requireNonNull(factExtractor, "factExtractor");
        this.evidenceGraphService = Objects.requireNonNull(evidenceGraphService, "evidenceGraphService");
        this.toolNames = Objects.requireNonNull(toolNames, "toolNames");
        this.runResultAdapter = Objects.requireNonNull(runResultAdapter, "runResultAdapter");
        this.agentRunIdAttribute = Objects.requireNonNull(agentRunIdAttribute, "agentRunIdAttribute");
    }

    public Map<String, Object> analyze(
        ChatModel activeChatModel,
        String query,
        String systemPrompt,
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result,
        int iteration,
        List<Map<String, Object>> previousEvidence,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata,
        BooleanSupplier cancellationCheck
    ) {
        List<Map<String, Object>> toolEvidence = interpretationToolEvidence(plan, result, iteration);
        DiagnosticRun diagnosticRun = diagnosticRun(result);
        Set<String> previouslyCompletedDiagnosticChecks = completedDiagnosticChecks(previousEvidence);
        boolean diagnosticCoverageComplete = diagnosticCoverageComplete(
            diagnosticRun,
            previouslyCompletedDiagnosticChecks
        );
        boolean diagnosticRetriesExhausted = diagnosticRun != null
            && diagnosticRun.confidenceEngine() != null
            && diagnosticRun.confidenceEngine().remainingRetries() == 0;
        boolean fallbackSufficient = result != null && result.success()
            && (diagnosticCoverageComplete || diagnosticRetriesExhausted);
        Map<String, Object> analysis = new LinkedHashMap<>();
        List<Object> evidenceUsed = new ArrayList<>();
        List<Object> missingEvidence = new ArrayList<>();
        List<Object> conflicts = new ArrayList<>();
        List<Object> nextActions = new ArrayList<>();
        List<Object> supersededMissingEvidence = new ArrayList<>();
        List<String> conclusions = new ArrayList<>();
        List<Map<String, Object>> currentHypotheses = new ArrayList<>();
        boolean explicitIterationDecision = false;
        boolean iterationSufficient = fallbackSufficient;
        if (diagnosticRun != null) {
            for (DiagnosticRun.CheckResult check : diagnosticRun.checks()) {
                if (check == null || !check.required()
                    || "completed".equals(check.status())
                    || previouslyCompletedDiagnosticChecks.contains(normalizedDiagnosticCheckId(check.checkId()))) {
                    continue;
                }
                Map<String, Object> gap = metadataOf(
                    "type", "diagnostic_check",
                    "checkId", check.checkId(),
                    "capability", check.capability(),
                    "dimension", check.dimension(),
                    "status", check.status(),
                    "reason", firstNonBlank(check.reason(), "missing_diagnostic_evidence")
                );
                missingEvidence.add(gap);
                nextActions.add(metadataOf(
                    "action", "complete_diagnostic_check",
                    "checkId", check.checkId(),
                    "capability", check.capability(),
                    "dimension", check.dimension(),
                    "reason", firstNonBlank(check.reason(), "missing_diagnostic_evidence")
                ));
            }
        }
        for (Map<String, Object> item : toolEvidence) {
            if (Boolean.TRUE.equals(item.get("success"))) {
                evidenceUsed.add(metadataOf(
                    "evidence_id", item.get("evidenceId"),
                    "basis", firstNonBlank(stringValue(item.get("reviewReason")), "successful MCP result")
                ));
            }
            addEvidenceAnalysisItems(missingEvidence, item.get("missingEvidence"));
            addEvidenceAnalysisItems(conflicts, item.get("conflicts"));
            currentHypotheses.addAll(evidenceGraphService.normalizeHypotheses(
                item.get("hypotheses"),
                stringValue(item.get("evidenceId"))
            ));
            String reason = stringValue(item.get("reviewReason"));
            if (reason != null && !reason.isBlank()
                && (!diagnosticCoverageComplete || satisfiedTemplateExecutionEvidence(item))) {
                conclusions.add(reason);
            }
            if (item.get("iterationSufficient") != null) {
                explicitIterationDecision = true;
                iterationSufficient &= booleanValue(item.get("iterationSufficient"));
            }
        }
        nextActions.addAll(pendingEvidenceNextActions(toolEvidence));
        if (diagnosticRun != null && diagnosticCoverageComplete && result != null && result.success()) {
            // Per-step reviewers run before downstream dependencies. Their interim
            // "not executed yet" gaps are therefore stale once the Runtime-owned
            // diagnostic contract proves that every required check is covered by
            // successful execution evidence. Preserve them for audit, but never let
            // them override the authoritative completion state.
            supersededMissingEvidence.addAll(missingEvidence);
            missingEvidence.clear();
            nextActions.clear();
            iterationSufficient = true;
        }
        analysis.put("sufficient", explicitIterationDecision ? iterationSufficient : fallbackSufficient);
        analysis.put("conclusion", conclusions.isEmpty()
            ? (fallbackSufficient ? "The round returned usable evidence." : "The round did not return sufficient evidence.")
            : String.join(" ", conclusions));
        analysis.put("evidence_used", evidenceUsed);
        analysis.put("missing_evidence", missingEvidence);
        analysis.put("conflicts", conflicts);
        analysis.put("next_actions", nextActions);

        List<Map<String, Object>> hypotheses = evidenceGraphService.mergeHypotheses(
            previousEvidence, currentHypotheses);
        Map<String, Object> evidenceGraph = evidenceGraphService.buildEvidenceGraph(
            iteration,
            previousEvidence,
            toolEvidence,
            hypotheses
        );
        boolean sufficient = booleanValue(firstObject(analysis, "sufficient", "satisfied", "complete"))
            && missingEvidence.isEmpty()
            && conflicts.isEmpty();
        AnalysisLoopContract analysisCoverage = analysisCoverage(
            query, diagnosticRun, previouslyCompletedDiagnosticChecks, toolEvidence,
            missingEvidence, nextActions, sufficient);
        double confidence = evidenceGraphService.evidenceConfidence(toolEvidence, hypotheses);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractVersion", "interpretation_evidence_iteration_v1");
        snapshot.put("iteration", iteration);
        snapshot.put("status", result == null ? "missing" : result.status());
        snapshot.put("executionSuccess", result != null && result.success());
        snapshot.put("sufficient", sufficient);
        snapshot.put("conclusion", firstNonBlank(
            stringValue(firstObject(analysis, "conclusion", "summary", "analysis")),
            result == null ? "No execution result was produced." : firstNonBlank(result.errorMessage(),
                result.success() ? "The round returned usable evidence." : "The round did not return sufficient evidence.")
        ));
        snapshot.put("evidenceUsed", evidenceAnalysisValue(analysis,
            "evidence_used", "evidenceUsed", "basis", "based_on"));
        snapshot.put("missingEvidence", evidenceAnalysisValue(analysis,
            "missing_evidence", "missingEvidence", "missing", "gaps"));
        snapshot.put("supersededMissingEvidence", supersededMissingEvidence);
        snapshot.put("conflicts", evidenceAnalysisValue(analysis,
            "conflicts", "contradictions", "uncertainty"));
        snapshot.put("hypotheses", hypotheses);
        snapshot.put("evidenceGraph", evidenceGraph);
        snapshot.put("confidence", confidence);
        snapshot.put("confidenceType", evidenceGraphService.evidenceConfidenceType(toolEvidence, hypotheses));
        snapshot.put("remainingMissing", missingEvidence);
        snapshot.put("nextActions", evidenceAnalysisValue(analysis,
            "next_actions", "nextActions", "next_queries", "nextQueries", "query_revisions", "queryRevisions"));
        snapshot.put("analysisCoverage", analysisCoverage.toMap());
        snapshot.put("gapRequests", analysisCoverage.gapRequests().stream()
            .map(AnalysisLoopContract.GapRequest::toMap).toList());
        snapshot.put("gapFingerprint", analysisCoverage.gapFingerprint());
        snapshot.put("toolEvidence", toolEvidence);
        if (diagnosticRun != null) {
            snapshot.put("diagnosticRun", diagnosticRun);
            snapshot.put("diagnosticCoverageComplete", diagnosticCoverageComplete);
            snapshot.put("previouslyCompletedDiagnosticChecks", previouslyCompletedDiagnosticChecks);
        }
        snapshot.put("createdAt", System.currentTimeMillis());

        addCandidateList(metadataList(metadata, "interpretationPlanEvidenceHistory"), List.of(snapshot));
        metadata.put("interpretationPlanEvidenceIterationCount", iteration);
        metadata.put("interpretationPlanEvidenceSufficient", sufficient);
        metadata.put("interpretationPlanEvidenceConfidence", confidence);
        metadata.put("interpretationPlanRemainingMissing", missingEvidence);
        metadata.put("interpretationPlanEvidenceGraph", evidenceGraph);
        metadata.put("interpretationPlanAnalysisCoverage", analysisCoverage.toMap());
        for (Map<String, Object> evidenceObject : toolEvidence) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                agentRunIdAttribute,
                "Captured " + firstNonBlank(stringValue(evidenceObject.get("evidenceId")), "MCP evidence") + ".",
                "interpretation_plan_evidence_object",
                metadataOf(
                    "type", "evidence",
                    "workflow", "interpretation_plan",
                    "lifecyclePhase", "evidence_capture",
                    "contractVersion", "evidence_object_v1",
                    "iteration", iteration,
                    "evidenceId", evidenceObject.get("evidenceId"),
                    "evidenceObject", evidenceObject
                )
            );
        }
        runResultAdapter.recordRuntimeObservation(
            runtimeAttributes,
            agentRunIdAttribute,
            "Evidence iteration " + iteration + " analyzed: "
                + firstNonBlank(stringValue(snapshot.get("conclusion")), "no conclusion"),
            "interpretation_plan_evidence",
            metadataOf(
                "type", "evidence",
                "workflow", "interpretation_plan",
                "lifecyclePhase", "evidence_analysis",
                "contractVersion", "interpretation_evidence_iteration_v1",
                "iteration", iteration,
                "sufficient", sufficient,
                "confidence", confidence,
                "evidenceSnapshot", snapshot
            )
        );
        if (!hypotheses.isEmpty()) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                agentRunIdAttribute,
                "Hypothesis state updated for evidence iteration " + iteration + ".",
                "interpretation_plan_hypothesis",
                metadataOf(
                    "type", "hypothesis",
                    "workflow", "interpretation_plan",
                    "lifecyclePhase", "hypothesis_evaluation",
                    "contractVersion", "interpretation_hypothesis_state_v1",
                    "iteration", iteration,
                    "hypotheses", hypotheses
                )
            );
        }
        if (collectionSize(evidenceGraph.get("relations")) > 0
            || collectionSize(evidenceGraph.get("rejectedRelations")) > 0) {
            runResultAdapter.recordRuntimeObservation(
                runtimeAttributes,
                agentRunIdAttribute,
                "Evidence graph updated for evidence iteration " + iteration + ".",
                "interpretation_plan_evidence_graph",
                metadataOf(
                    "type", "evidence_graph",
                    "workflow", "interpretation_plan",
                    "lifecyclePhase", "evidence_relationship",
                    "contractVersion", "evidence_graph_v1",
                    "iteration", iteration,
                    "evidenceGraph", evidenceGraph
                )
            );
        }
        return snapshot;
    }

    private AnalysisLoopContract analysisCoverage(
        String query,
        DiagnosticRun diagnosticRun,
        Set<String> previouslyCompleted,
        List<Map<String, Object>> toolEvidence,
        List<Object> missingEvidence,
        List<Object> nextActions,
        boolean sufficient
    ) {
        List<String> successfulEvidenceIds = toolEvidence == null ? List.of() : toolEvidence.stream()
            .filter(item -> item != null && Boolean.TRUE.equals(item.get("success")))
            .map(item -> stringValue(item.get("evidenceId")))
            .filter(id -> id != null && !id.isBlank()).distinct().toList();
        List<AnalysisLoopContract.QuestionCoverage> questions = new ArrayList<>();
        List<AnalysisLoopContract.GapRequest> gaps = new ArrayList<>();
        Set<String> prior = previouslyCompleted == null ? Set.of() : previouslyCompleted;
        if (diagnosticRun != null && diagnosticRun.checks() != null) {
            for (DiagnosticRun.CheckResult check : diagnosticRun.checks()) {
                if (check == null) continue;
                String questionId = firstNonBlank(check.checkId(), "diagnostic-question-" + (questions.size() + 1));
                String capability = firstNonBlank(check.capability(), check.dimension());
                boolean covered = "completed".equals(check.status())
                    || prior.contains(normalizedDiagnosticCheckId(check.checkId()));
                AnalysisLoopContract.Criticality criticality = check.required()
                    ? AnalysisLoopContract.Criticality.CORE
                    : AnalysisLoopContract.Criticality.SUPPORTING;
                questions.add(new AnalysisLoopContract.QuestionCoverage(
                    questionId,
                    firstNonBlank(firstNonBlank(check.dimension(), capability), questionId),
                    criticality,
                    covered ? AnalysisLoopContract.CoverageStatus.SUPPORTED
                        : AnalysisLoopContract.CoverageStatus.UNSUPPORTED,
                    capability == null ? List.of() : List.of(capability),
                    covered ? successfulEvidenceIds : List.of(),
                    covered ? List.of() : List.of(firstNonBlank(check.reason(), "required evidence is missing"))
                ));
                if (!covered && check.required()) {
                    gaps.add(new AnalysisLoopContract.GapRequest(
                        questionId,
                        "Retrieve evidence for " + firstNonBlank(firstNonBlank(check.dimension(), capability), questionId),
                        capability == null ? List.of() : List.of(capability),
                        "USER_REQUESTED_SCOPE",
                        firstNonBlank(check.dimension(), "PRODUCER_DECLARED_GRAIN"),
                        AnalysisLoopContract.Criticality.CORE,
                        firstNonBlank(check.reason(), "required diagnostic capability is not covered")
                    ));
                }
            }
        }
        if (questions.isEmpty()) {
            boolean partial = !successfulEvidenceIds.isEmpty() && !sufficient;
            List<String> missing = missingEvidence == null ? List.of() : missingEvidence.stream()
                .map(String::valueOf).filter(value -> !value.isBlank()).distinct().toList();
            questions.add(new AnalysisLoopContract.QuestionCoverage(
                "primary-goal", firstNonBlank(query, "Complete the requested business analysis"),
                AnalysisLoopContract.Criticality.CORE,
                sufficient ? AnalysisLoopContract.CoverageStatus.SUPPORTED
                    : partial ? AnalysisLoopContract.CoverageStatus.PARTIAL
                    : AnalysisLoopContract.CoverageStatus.UNSUPPORTED,
                requiredCapabilities(missingEvidence, nextActions), successfulEvidenceIds, missing));
            if (!sufficient && (!missing.isEmpty() || (nextActions != null && !nextActions.isEmpty()))) {
                gaps.add(new AnalysisLoopContract.GapRequest(
                    "primary-goal", "Retrieve evidence that closes the remaining business-question gap",
                    requiredCapabilities(missingEvidence, nextActions), "USER_REQUESTED_SCOPE",
                    "PRODUCER_DECLARED_GRAIN", AnalysisLoopContract.Criticality.CORE,
                    missing.isEmpty() ? "an evidence refinement action remains" : String.join("; ", missing)
                ));
            }
        }
        return AnalysisLoopContract.of(query, questions, gaps);
    }

    private List<String> requiredCapabilities(List<Object> missingEvidence, List<Object> nextActions) {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        for (Object raw : List.of(missingEvidence == null ? List.of() : missingEvidence,
            nextActions == null ? List.of() : nextActions)) {
            if (!(raw instanceof Iterable<?> values)) continue;
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> map)) continue;
                Map<String, Object> item = asStringObjectMap(map);
                for (String key : List.of("capability", "dimension", "requiredCapability", "retrievalGoal")) {
                    String capability = stringValue(item.get(key));
                    if (capability != null && !capability.isBlank()) capabilities.add(capability);
                }
            }
        }
        return List.copyOf(capabilities);
    }

    private boolean satisfiedTemplateExecutionEvidence(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        Map<String, Object> contract = asMap(evidence.get("templateExecutionReview"));
        return Boolean.TRUE.equals(booleanValue(contract.get("satisfied")))
            && Boolean.TRUE.equals(booleanValue(evidence.get("success")));
    }

    private DiagnosticRun diagnosticRun(InterpretationPlanRuntime.ExecutionResult result) {
        Object value = result == null || result.metadata() == null
            ? null
            : result.metadata().get("diagnosticRun");
        return value instanceof DiagnosticRun run ? run : null;
    }

    private Set<String> completedDiagnosticChecks(List<Map<String, Object>> previousEvidence) {
        Set<String> completed = new LinkedHashSet<>();
        for (Map<String, Object> snapshot : previousEvidence == null ? List.<Map<String, Object>>of() : previousEvidence) {
            Object value = snapshot == null ? null : snapshot.get("diagnosticRun");
            if (!(value instanceof DiagnosticRun run) || run.checks() == null) {
                continue;
            }
            run.checks().stream()
                .filter(check -> check != null && "completed".equals(check.status()))
                .map(DiagnosticRun.CheckResult::checkId)
                .map(this::normalizedDiagnosticCheckId)
                .filter(checkId -> !checkId.isBlank())
                .forEach(completed::add);
        }
        return completed;
    }

    private boolean diagnosticCoverageComplete(DiagnosticRun run, Set<String> previouslyCompleted) {
        if (run == null || run.checks() == null) {
            return true;
        }
        Set<String> prior = previouslyCompleted == null ? Set.of() : previouslyCompleted;
        return run.checks().stream()
            .filter(check -> check != null && check.required())
            .allMatch(check -> "completed".equals(check.status())
                || prior.contains(normalizedDiagnosticCheckId(check.checkId())));
    }

    private String normalizedDiagnosticCheckId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void addEvidenceAnalysisItems(List<Object> target, Object value) {
        if (target == null || value == null) {
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    target.add(item);
                }
            }
            return;
        }
        if (!String.valueOf(value).isBlank()) {
            target.add(value);
        }
    }

    public List<Map<String, Object>> interpretationToolEvidence(
        InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result,
        int iteration
    ) {
        if (result == null || result.steps() == null || result.steps().isEmpty()) {
            return List.of();
        }
        Map<Integer, InterpretationPlan.Step> plannedSteps = new LinkedHashMap<>();
        if (plan != null && plan.steps() != null) {
            for (InterpretationPlan.Step step : plan.steps()) {
                if (step != null && step.id() != null) {
                    plannedSteps.put(step.id(), step);
                }
            }
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            if (step == null || step.stepId() == null || "final_answer".equals(step.actionType())) {
                continue;
            }
            String evidenceId = "iteration:" + iteration + ":step:" + step.stepId()
                + ":tool:" + firstNonBlank(step.toolName(), step.actionType());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("contractVersion", "evidence_object_v1");
            item.put("evidenceId", evidenceId);
            item.put("iteration", iteration);
            item.put("stepId", step.stepId());
            item.put("tool", firstNonBlank(step.toolName(), step.actionType()));
            InterpretationPlan.Step plannedStep = plannedSteps.get(step.stepId());
            item.put("input", plannedStep == null || plannedStep.input() == null ? Map.of() : plannedStep.input());
            item.put("success", step.success());
            item.put("reviewSatisfied", step.metadata() == null ? null
                : step.metadata().get("toolResultReviewSatisfied"));
            item.put("reviewReason", step.metadata() == null ? null
                : step.metadata().get("toolResultReviewReason"));
            item.put("iterationSufficient", step.metadata() == null ? null
                : step.metadata().get("evidenceIterationSufficient"));
            item.put("evidenceBasis", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("evidenceBasis", List.of()));
            item.put("missingEvidence", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("missingEvidence", List.of()));
            item.put("conflicts", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("evidenceConflicts", List.of()));
            List<Object> stepNextActions = new ArrayList<>();
            if (step.metadata() != null) {
                addEvidenceAnalysisItems(stepNextActions,
                    step.metadata().getOrDefault("nextActions", List.of()));
            }
            Object executionContractSource = step.toolExecution() != null
                && step.toolExecution().output() != null
                && step.toolExecution().output().getData() != null
                ? step.toolExecution().output().getData()
                : step.output();
            stepNextActions.addAll(discoveredExecutorActions(
                executionContractSource, step.toolName(), step.stepId()));
            item.put("nextActions", List.copyOf(stepNextActions));
            Map<String, Object> evidenceEvaluation = step.metadata() == null
                ? Map.of()
                : asMap(step.metadata().get("evidenceEvaluation"));
            item.put("shouldExpandQuery", evidenceEvaluation.get("shouldExpandQuery"));
            item.put("retryable", step.metadata() == null ? null : step.metadata().get("retryable"));
            item.put("resultCode", step.metadata() == null ? null : step.metadata().get("resultCode"));
            item.put("hypotheses", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("hypotheses", List.of()));
            item.put("templateExecutionReview", step.metadata() == null ? Map.of()
                : step.metadata().getOrDefault("templateExecutionReview", Map.of()));
            item.put("missingParameters", step.metadata() == null ? List.of()
                : step.metadata().getOrDefault("templateExecutionMissingParameters", List.of()));
            item.put("retryInputChanges", step.metadata() == null ? Map.of()
                : step.metadata().getOrDefault("templateExecutionRetryInputChanges", Map.of()));
            item.put("templateReselectionRequired", step.metadata() != null
                && Boolean.TRUE.equals(step.metadata().get("templateReselectionRequired")));
            if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
                item.put("error", step.errorMessage());
            }
            item.put("output", step.output());
            item.put("outputFacts", factExtractor.structuredOutputFacts(step.output()));
            Map<String, Object> evidenceMetadata = new LinkedHashMap<>();
            evidenceMetadata.put("timestamp", System.currentTimeMillis());
            evidenceMetadata.put("source", evidenceSource(step));
            evidenceMetadata.put("confidence", step.metadata() == null
                ? null
                : step.metadata().get("toolResultReviewConfidence"));
            evidenceMetadata.put("success", step.success());
            evidenceMetadata.put("durationMs", step.durationMs());
            evidenceMetadata.put("reviewSatisfied", item.get("reviewSatisfied"));
            evidenceMetadata.put("reviewReason", item.get("reviewReason"));
            item.put("metadata", evidenceMetadata);
            item.put("evidenceQuality", evidenceQuality(step, item, evidenceMetadata));
            item.put("rawResultStored", true);
            evidence.add(item);
        }
        return List.copyOf(evidence);
    }

    /**
     * Converts a discovery result's published execution contract into a deterministic
     * evidence-refinement action. The Runtime reads the executor role from returned metadata;
     * it does not infer an executor from business intent, template names, or tool-name families.
     */
    public List<Map<String, Object>> discoveredExecutorActions(Object output,
                                                        String sourceTool,
                                                        Integer sourceStepId) {
        Set<String> executionTools = new LinkedHashSet<>();
        collectExecutionTools(output, executionTools, 0);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String executionTool : executionTools) {
            if (executionTool == null || executionTool.isBlank()
                || toolNames.sameToolName(sourceTool, executionTool)) {
                continue;
            }
            actions.add(metadataOf(
                "action", "execute_discovered_template",
                "tool", executionTool,
                "requiredExecution", true,
                "source", "tool_output_execution_contract",
                "discoveryStepId", sourceStepId,
                "dependsOnTools", sourceTool == null ? List.of() : List.of(sourceTool),
                "reason", "Discovery metadata declares an executor; discovery alone is not business evidence."
            ));
        }
        return List.copyOf(actions);
    }

    private void collectExecutionTools(Object value, Set<String> target, int depth) {
        if (value == null || target == null || depth > 10 || target.size() >= 16) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String field = key == null ? "" : String.valueOf(key);
                if (("executionTool".equals(field) || "execution_tool".equals(field))
                    && nested != null && !String.valueOf(nested).isBlank()) {
                    target.add(String.valueOf(nested).trim());
                } else {
                    collectExecutionTools(nested, target, depth + 1);
                }
            });
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                collectExecutionTools(item, target, depth + 1);
            }
        }
    }

    private Map<String, Object> evidenceQuality(
        InterpretationPlanRuntime.StepExecution step,
        Map<String, Object> evidence,
        Map<String, Object> evidenceMetadata
    ) {
        Map<String, Object> output = asMap(step == null ? null : step.output());
        Object explicitReliability = firstObject(output,
            "sourceReliability", "source_reliability", "reliability");
        Object explicitFreshness = firstObject(output,
            "freshness", "freshnessScore", "freshness_score");
        Double sourceReliabilityScore = explicitQualityScore(explicitReliability);
        Double freshnessScore = explicitQualityScore(explicitFreshness);
        int missingCount = collectionSize(evidence == null ? null : evidence.get("missingEvidence"));
        int conflictCount = collectionSize(evidence == null ? null : evidence.get("conflicts"));
        boolean hasOutput = step != null && step.output() != null
            && !stringify(step.output()).isBlank()
            && !"{}".equals(stringify(step.output()))
            && !"[]".equals(stringify(step.output()));
        double completeness = step != null && step.success() && hasOutput
            ? clampScore(1.0 / (1.0 + (missingCount * 0.35)))
            : 0.0;
        double consistency = clampScore(1.0 / (1.0 + conflictCount));
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("contractVersion", "evidence_quality_v1");
        quality.put("sourceReliability", sourceReliabilityScore == null
            ? unknownQualityMetric("Source reliability metadata is unavailable.")
            : assessedQualityMetric(sourceReliabilityScore, "COMPUTED",
                "Derived from explicit source reliability metadata returned by the tool."));
        quality.put("freshness", freshnessScore == null
            ? unknownQualityMetric("Freshness metadata is unavailable.")
            : assessedQualityMetric(freshnessScore, "COMPUTED",
                "Derived from explicit freshness metadata returned by the tool."));
        quality.put("completeness", assessedQualityMetric(
            completeness,
            "COMPUTED",
            hasOutput
                ? "Computed from execution success, returned output, and declared evidence gaps."
                : "No usable output was returned."
        ));
        quality.put("consistency", assessedQualityMetric(
            consistency,
            "COMPUTED",
            conflictCount == 0
                ? "No evidence conflict was declared for this result."
                : "Reduced by declared evidence conflicts."
        ));
        Object rawModelConfidence = evidenceMetadata.get("confidence");
        Double modelConfidenceScore = explicitQualityScore(rawModelConfidence);
        quality.put("modelConfidence", modelConfidenceScore == null
            ? unknownQualityMetric("The model did not provide a confidence estimate.", "MODEL_ESTIMATED")
            : assessedQualityMetric(modelConfidenceScore, "MODEL_ESTIMATED",
                "Model-estimated confidence; excluded from evidence quality dimensions."));
        quality.put("aggregatePolicy", "NO_PERSISTED_TOTAL_SCORE");
        quality.put("method", "runtime_structural_quality_v1");
        quality.put("signals", metadataOf(
            "explicitSourceReliability", sourceReliabilityScore != null,
            "explicitFreshness", freshnessScore != null,
            "missingEvidenceCount", missingCount,
            "conflictCount", conflictCount,
            "hasOutput", hasOutput
        ));
        return quality;
    }

    private Double explicitQualityScore(Object value) {
        Object candidate = value;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> metric = asStringObjectMap(raw);
            if ("UNKNOWN".equalsIgnoreCase(stringValue(metric.get("status")))) {
                return null;
            }
            candidate = metric.get("value");
        }
        if (candidate instanceof Number number) {
            return clampScore(number.doubleValue());
        }
        if (candidate == null || String.valueOf(candidate).isBlank()) {
            return null;
        }
        try {
            return clampScore(Double.parseDouble(String.valueOf(candidate).trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> assessedQualityMetric(double value, String type, String reason) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("value", clampScore(value));
        metric.put("status", "ASSESSED");
        metric.put("type", firstNonBlank(type, "COMPUTED"));
        metric.put("reason", firstNonBlank(reason, "Quality dimension was assessed."));
        return metric;
    }

    private Map<String, Object> unknownQualityMetric(String reason) {
        return unknownQualityMetric(reason, "NOT_ASSESSED");
    }

    private Map<String, Object> unknownQualityMetric(String reason, String type) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("value", null);
        metric.put("status", "UNKNOWN");
        metric.put("type", firstNonBlank(type, "NOT_ASSESSED"));
        metric.put("reason", firstNonBlank(reason, "Quality dimension cannot be assessed."));
        return metric;
    }

    private int collectionSize(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return value == null || String.valueOf(value).isBlank() ? 0 : 1;
    }

    private String evidenceSource(InterpretationPlanRuntime.StepExecution step) {
        Map<String, Object> output = asMap(step == null ? null : step.output());
        return firstNonBlank(
            stringValue(firstObject(output, "source", "provider", "index", "indexName", "dataset")),
            step == null ? null : firstNonBlank(step.toolName(), step.actionType())
        );
    }

    private Object evidenceAnalysisValue(Map<String, Object> analysis, String... keys) {
        Object value = firstObject(analysis, keys);
        return value == null ? List.of() : value;
    }
    public List<Object> pendingEvidenceNextActions(List<Map<String, Object>> toolEvidence) {
        if (toolEvidence == null || toolEvidence.isEmpty()) {
            return List.of();
        }
        List<Object> pending = new ArrayList<>();
        for (int index = 0; index < toolEvidence.size(); index++) {
            Map<String, Object> source = toolEvidence.get(index);
            if (source == null) {
                continue;
            }
            Object rawActions = source.get("nextActions");
            if (!(rawActions instanceof Iterable<?> actions)) {
                continue;
            }
            for (Object rawAction : actions) {
                if (!(rawAction instanceof Map<?, ?> rawActionMap)) {
                    addEvidenceAnalysisItems(pending, rawAction);
                    continue;
                }
                Map<String, Object> action = asStringObjectMap(rawActionMap);
                String requestedTool = stringValue(firstObject(action,
                    "tool", "toolName", "tool_name"));
                boolean requiredExecution = booleanValue(firstObject(action,
                    "requiredExecution", "required_execution"));
                if (requestedTool != null
                    && !requiredExecution
                    && Boolean.FALSE.equals(source.get("shouldExpandQuery"))) {
                    continue;
                }
                boolean alreadyExecutedSuccessfully = false;
                if (requestedTool != null) {
                    for (Map<String, Object> executed : toolEvidence) {
                        if (executed != null
                            && Boolean.TRUE.equals(executed.get("success"))
                            && toolNames.sameToolName(requestedTool, stringValue(executed.get("tool")))) {
                            alreadyExecutedSuccessfully = true;
                            break;
                        }
                    }
                }
                boolean evidenceBackedRevision = Boolean.TRUE.equals(source.get("shouldExpandQuery"))
                    && hasMaterialEvidenceActionRevision(action);
                if (!alreadyExecutedSuccessfully || evidenceBackedRevision) {
                    pending.add(action);
                }
            }
        }
        return List.copyOf(pending);
    }

    private boolean hasMaterialEvidenceActionRevision(Map<String, Object> action) {
        if (action == null || action.isEmpty()) {
            return false;
        }
        Object changes = firstObject(action,
            "input_changes", "inputChanges", "retry_input_changes", "retryInputChanges");
        if (changes instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (changes instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return changes != null && !String.valueOf(changes).isBlank();
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

    private double clampScore(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
