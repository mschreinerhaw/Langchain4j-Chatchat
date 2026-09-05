package com.chatchat.agents.orchestration;

import static com.chatchat.agents.orchestration.analysis.graph.InterpretationAnalysisGraph.Phase.*;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.orchestration.analysis.graph.InterpretationAnalysisGraph.Phase;
import com.chatchat.agents.orchestration.evidence.RecoveredBatchEvidenceBridge;
import com.chatchat.agents.orchestration.planning.validation.AgentPlanBudgetPolicy;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedRetrievalBridge;
import com.chatchat.agents.orchestration.tool.ToolCallFingerprint;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanRewriter;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;
import com.chatchat.common.interaction.InteractionToolTrace;

import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Per-invocation node actions. Graph edges own ordering; the host supplies existing Runtime
 * operations.
 */
final class InterpretationAnalysisSession {
    private final AgentOrchestrationEngine host;
    private final ChatModel activeChatModel;
    private final String query;
    private final String systemPrompt;
    private final String tenantId;
    private final String requestId;
    private final String conversationId;
    private final String userId;
    private final List<String> tools;
    private final List<InteractionToolTrace> traces;
    private final List<String> observations;
    private final Map<String, Object> metadata;
    private final List<String> documentIds;
    private final List<String> documentTags;
    private final int webSearchResultLimit;
    private final int maxToolCalls;
    private final BooleanSupplier cancellationCheck;

    InterpretationAnalysisSession(
            AgentOrchestrationEngine host,
            InterpretationPlan plan,
            ChatModel activeChatModel,
            String query,
            String systemPrompt,
            String tenantId,
            String requestId,
            String conversationId,
            String userId,
            List<String> tools,
            Map<String, Object> runtimeAttributes,
            List<InteractionToolTrace> traces,
            List<String> observations,
            Map<String, Object> metadata,
            List<String> documentIds,
            List<String> documentTags,
            int webSearchResultLimit,
            int maxToolCalls,
            BooleanSupplier cancellationCheck) {
        this.host = host;
        this.plan = plan;
        this.activeChatModel = activeChatModel;
        this.query = query;
        this.systemPrompt = systemPrompt;
        this.tenantId = tenantId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.tools = tools;
        this.runtimeAttributes = runtimeAttributes;
        this.traces = traces;
        this.observations = observations;
        this.metadata = metadata;
        this.documentIds = documentIds;
        this.documentTags = documentTags;
        this.webSearchResultLimit = webSearchResultLimit;
        this.maxToolCalls = maxToolCalls;
        this.cancellationCheck = cancellationCheck;
    }

    private InterpretationPlan plan;
    private Map<String, Object> runtimeAttributes;
    private InterpretationPlanRuntime.ExecutionResult firstResult;
    private int rewriteCount;
    private AgentPlanBudgetPolicy.BudgetCaps budgetCaps;
    private Object authoritativeWorkflowDag;
    private String authoritativeWorkflowTaskId;
    private boolean hasAuthoritativeWorkflowDag;
    private InterpretationPlanValidator validator;
    private InterpretationPlanRuntime runtime;
    private InterpretationPlan initialPipelinePlan;
    private int resumedRewriteCount;
    private List<InterpretationPlanRuntime.ExecutionResult> planAttemptResults;
    private List<Map<String, Object>> evidenceHistory;
    private InterpretationPlanValidator.ValidationResult initialEvaluation;
    private Map<String, Object> firstEvidence;
    private AgentOrchestrator.RecordCoverageBundle latestRecordCoverage;
    private int configuredMaxRewriteTimes;
    private boolean firstEvidenceAvailable;
    private boolean augmentationOverrideAvailable;
    private EvidenceAugmentationPolicy.Outcome latestAugmentationDecision;
    private InterpretationPlanRewriter rewriter;
    private InterpretationPlan currentPlan;
    private InterpretationPlanRuntime.ExecutionResult currentResult;
    private Map<Integer, InterpretationPlanRuntime.ReusableStep> reusablePlanSteps;
    private int maxRewriteTimes;
    private int evidenceDrivenRewriteLimit;
    private boolean duplicateToolPlanSuppressed;
    private boolean usablePartialAnalysis;
    private Set<String> completedTools;
    private InterpretationPlanRewriter.RewriteResult rewrite;
    private InterpretationPlan rewrittenPlan;
    private Object rewriteWorkflowDag;
    private String rewriteStage;
    private Map<String, Object> currentEvidence;
    AgentOrchestrator.AgentExecutionResult completion;
    Phase prepare() {
        host.runtimeGuard.checkCancelled(cancellationCheck);
        runtimeAttributes = host.interpretationPlanInitialAttributes(runtimeAttributes, traces);
        budgetCaps = AgentPlanBudgetPolicy.fromRuntimeAttributes(runtimeAttributes);
        AgentPlanBudgetPolicy.ApplyResult budgetResult = AgentPlanBudgetPolicy.apply(plan, budgetCaps);
        plan = budgetResult.plan();
        metadata.put("runtimePlanLatencyBudgetMs", budgetCaps.latencyBudgetMs());
        metadata.put(
                "effectivePlanLatencyBudgetMs",
                plan.executionPolicy() == null ? null : plan.executionPolicy().latencyBudgetMs());
        metadata.put("modelPlanLatencyBudgetEnforced", false);
        authoritativeWorkflowDag =
                runtimeAttributes == null
                        ? null
                        : runtimeAttributes.get("authoritativeWorkflowDag");
        authoritativeWorkflowTaskId =
                runtimeAttributes == null
                        ? null
                        : stringValue(runtimeAttributes.get("authoritativeWorkflowTaskId"));
        hasAuthoritativeWorkflowDag =
                authoritativeWorkflowDag instanceof Collection<?> collection
                        && !collection.isEmpty();
        List<String> authoritativeWorkflowDagPasses = List.of();
        Map<String, Object> authoritativeWorkflowDagRepair = Map.of();
        if (hasAuthoritativeWorkflowDag) {
            InterpretationPlanOptimizer.OptimizationResult workflowDagOptimization =
                    new InterpretationPlanOptimizer(host.toolRegistry)
                            .optimize(plan, authoritativeWorkflowDag);
            plan = workflowDagOptimization.plan() == null ? plan : workflowDagOptimization.plan();
            authoritativeWorkflowDagPasses = workflowDagOptimization.appliedPasses();
            authoritativeWorkflowDagRepair = workflowDagOptimization.repairResult().auditMetadata();
        }
        metadata.put("interpretationPlanPipeline", true);
        metadata.put("interpretationPlanVersion", plan.version());
        metadata.put("authoritativeWorkflowDagPasses", authoritativeWorkflowDagPasses);
        if (!authoritativeWorkflowDagRepair.isEmpty()) {
            metadata.put("authoritativeWorkflowDagRepair", authoritativeWorkflowDagRepair);
        }
        if (budgetCaps.configured()) {
            metadata.put("agentBudgetCaps", budgetCaps.metadata());
            metadata.put("agentBudgetAdjusted", budgetResult.adjusted());
        }
        host.planSnapshotService.saveGenerated(
                "initial", plan, tenantId, requestId, runtimeAttributes, metadata);
        host.planEvolutionAuditor.recordEvolution(
                null, plan, 1, "INITIAL", List.of(), runtimeAttributes, metadata);
        validator = new InterpretationPlanValidator();
        Map<String, Object> pipelineRuntimeAttributes = runtimeAttributes;
        runtime =
                new InterpretationPlanRuntime(
                        host.toolRuntimeService,
                        validator,
                        new InterpretationPlanOptimizer(host.toolRegistry),
                        host.runStore,
                        request ->
                                host.reviewInterpretationPlanToolResult(
                                        activeChatModel,
                                        query,
                                        systemPrompt,
                                        cancellationCheck,
                                        request,
                                        pipelineRuntimeAttributes),
                        request ->
                                host.decideInterpretationPlanDagStep(
                                        activeChatModel,
                                        query,
                                        systemPrompt,
                                        cancellationCheck,
                                        request,
                                        pipelineRuntimeAttributes),
                        request -> {
                            String stepTool =
                                    request.step() == null ? null : request.step().toolName();
                            ModelAssistedRetrievalBridge.RetrievalEvidenceContext evidenceContext =
                                    host.templateRetrievalEvidenceContext(
                                            query, request.completed());
                            Map<String, Object> contextual =
                                    host.modelAssistedContextParameterBridge.propose(
                                            activeChatModel,
                                            stepTool,
                                            request.input(),
                                            evidenceContext);
                            return host.modelAssistedRetrievalBridge
                                    .enrichWithGate(
                                            activeChatModel, stepTool, contextual, evidenceContext)
                                    .argumentsWithGateMarker();
                        },
                        host.planToolExecutionPort,
                        host.planDagControlPort);
        runtime.setNodeAttemptStore(host.nodeAttemptStore);
        AgentPlanPipelineContinuation resumedPipeline = host.planExecutionBridge.resumedPipeline();
        initialPipelinePlan = resumedPipeline == null ? plan : resumedPipeline.initialPlan();
        resumedRewriteCount = resumedPipeline == null ? 0 : resumedPipeline.rewriteCount();
        planAttemptResults =
                new ArrayList<>(
                        resumedPipeline == null ? List.of() : resumedPipeline.attemptResults());
        evidenceHistory =
                new ArrayList<>(
                        resumedPipeline == null ? List.of() : resumedPipeline.evidenceHistory());
        initialEvaluation =
                validator.validate(
                        plan,
                        host.toolRegistry,
                        new LinkedHashSet<>(tools == null ? List.of() : tools),
                        authoritativeWorkflowDag,
                        authoritativeWorkflowTaskId);
        host.recordInterpretationPlanEvaluation(
                "initial", initialEvaluation, runtimeAttributes, metadata);

        return INITIAL_DATA;
    }

    Phase initialData() {
        if (initialEvaluation.valid()) {
            host.recordInterpretationPlanExecutionStarted(
                    "initial", plan, runtimeAttributes, metadata);
            InterpretationPlanRuntime.ExecutionRequest executionRequest =
                    host.planExecutionRequest(
                            plan,
                            tenantId,
                            requestId,
                            conversationId,
                            userId,
                            tools,
                            host.workflowAttemptAttributes(runtimeAttributes, 0));
            InterpretationPlanRuntime.ExecutionResult resumedResult =
                    host.planExecutionBridge.consume(plan, ToolCallFingerprint.forPlan(plan));
            if (resumedResult != null) {
                firstResult = resumedResult;
            } else {
                completion = host.rejectPlanExceedingRequestBudget(
                    plan, runtimeAttributes, traces, metadata, observations);
                if (completion != null) return END;

                host.suspendForDurablePlanExecution(
                        initialPipelinePlan,
                        plan,
                        executionRequest,
                        resumedRewriteCount,
                        host.maxRewriteTimes(initialPipelinePlan),
                        planAttemptResults,
                        evidenceHistory,
                        runtimeAttributes,
                        traces,
                        observations,
                        metadata);
                firstResult =
                        runtime.execute(
                                executionRequest,
                                host.planKernelScope(
                                        tenantId,
                                        userId,
                                        requestId,
                                        conversationId,
                                        runtimeAttributes));
            }
        } else {
            firstResult = host.planEvaluationFailure("initial", initialEvaluation);
        }
        host.recordPlanRuntimeResult("initial", firstResult, traces, observations, metadata);
        host.planSnapshotService.saveExecution(
                "initial_result",
                plan,
                tenantId,
                requestId,
                runtimeAttributes,
                metadata,
                firstResult);
        host.checkCancelledUnlessBatchEvidence(cancellationCheck, firstResult, metadata);

        if (firstResult.approvalRequired()) {
            metadata.put("stopReason", "confirmation_required");
            metadata.put("confirmationRequired", true);
            completion = host.answerFinalizer.finishExecution("", traces, metadata, observations);
            return END;
        }
        firstResult =
                host.consumePlanExecutionResult(
                        "initial", plan, firstResult, runtimeAttributes, observations, metadata);
        planAttemptResults.add(firstResult);

        return INITIAL_ANALYSIS;
    }

    Phase initialAnalysis() {
        firstEvidence =
                host.analyzeInterpretationPlanEvidence(
                        activeChatModel,
                        query,
                        systemPrompt,
                        plan,
                        firstResult,
                        1,
                        evidenceHistory,
                        runtimeAttributes,
                        metadata,
                        cancellationCheck);
        latestRecordCoverage =
                host.analyzeClaimAdmissionCoverage(
                        activeChatModel,
                        query,
                        firstResult,
                        planAttemptResults,
                        runtimeAttributes,
                        metadata,
                        cancellationCheck);
        firstEvidence =
                host.semanticClaimCoordinator.evaluate(
                        firstEvidence,
                        latestRecordCoverage.summaryResults(),
                        1,
                        runtimeAttributes,
                        metadata);
        evidenceHistory.add(firstEvidence);
        configuredMaxRewriteTimes = host.maxRewriteTimes(initialPipelinePlan);
        firstEvidenceAvailable = host.usableEvidenceAvailable(firstEvidence);
        boolean actionableEvidenceRefinementAvailable =
                !host.evidenceRefinementRequiredTools(evidenceHistory, tools).isEmpty();
        augmentationOverrideAvailable =
                configuredMaxRewriteTimes == 0
                        && (firstEvidenceAvailable || actionableEvidenceRefinementAvailable)
                        && host.MAX_INTERPRETATION_PLAN_ATTEMPTS > 1;
        latestAugmentationDecision =
                host.decideEvidenceAugmentation(
                        firstEvidence,
                        firstResult,
                        host.evidenceExplorationAvailable(
                                firstEvidence,
                                firstResult,
                                tools,
                                configuredMaxRewriteTimes > 0 || augmentationOverrideAvailable),
                        false,
                        metadata);
        host.recordEvidenceAugmentationDecision(
                latestAugmentationDecision, 1, runtimeAttributes, metadata);
        return latestAugmentationDecision.decision() == EvidenceAugmentationPolicy.Decision.COMPLETE
                ? FINAL_INITIAL
                : PREPARE_REFINEMENT;
    }

    Phase prepareRefinement() {
        rewriter = new InterpretationPlanRewriter(activeChatModel, host.objectMapper, validator);
        currentPlan = plan;
        currentResult = firstResult;
        reusablePlanSteps =
                host.analysisRefinementCoordinator.reusableSteps(
                        Map.of(), currentPlan, currentResult);
        boolean executionRecoveryRequired = !firstResult.success();
        boolean templateExecutionRetryRequested = host.templateExecutionRetryRequested(firstResult);
        maxRewriteTimes =
                host.initialRewriteLimit(
                        configuredMaxRewriteTimes,
                        latestAugmentationDecision,
                        augmentationOverrideAvailable,
                        executionRecoveryRequired,
                        templateExecutionRetryRequested,
                        tools != null && !tools.isEmpty());
        evidenceDrivenRewriteLimit =
                host.evidenceDrivenRewriteLimit(
                        configuredMaxRewriteTimes,
                        latestAugmentationDecision,
                        evidenceHistory,
                        tools);
        if (evidenceDrivenRewriteLimit > maxRewriteTimes) {
            maxRewriteTimes = evidenceDrivenRewriteLimit;
            metadata.put("evidenceDrivenRewriteBudgetApplied", true);
            metadata.put(
                    "evidenceDrivenRewriteBudgetReason",
                    "The evidence chain contains an available-tool next action, so refinement"
                        + " remains enabled within the runtime attempt ceiling.");
        }
        if (templateExecutionRetryRequested
                && latestAugmentationDecision.continueLoop()
                && tools != null
                && !tools.isEmpty()) {
            metadata.put("templateExecutionRetryBounded", true);
            metadata.put("templateExecutionRetryLimit", 1);
            metadata.put(
                    "templateExecutionRetryStrategy",
                    "EVIDENCE_BASED_PARAMETER_REPAIR_OR_TEMPLATE_RESELECTION");
        }
        duplicateToolPlanSuppressed = false;
        usablePartialAnalysis =
                latestAugmentationDecision.decision()
                                == EvidenceAugmentationPolicy.Decision.ANALYZE_WITH_LIMITATIONS
                        && firstEvidenceAvailable;
        metadata.put("interpretationPlanConfiguredMaxRewriteTimes", configuredMaxRewriteTimes);
        metadata.put("interpretationPlanMaxRewriteTimes", maxRewriteTimes);
        if (maxRewriteTimes > configuredMaxRewriteTimes) {
            metadata.put("evidenceAugmentationOverrideApplied", true);
            metadata.put(
                    "evidenceAugmentationOverrideReason",
                    "A non-empty MCP result had an actionable evidence gap; one bounded refinement"
                        + " round was preserved.");
        }
        rewriteCount = resumedRewriteCount;
        return REFINEMENT_GATE;
    }

    Phase refinementGate() {
        if (rewriteCount >= maxRewriteTimes) return FINALIZE;
        rewriteCount++;
        return REFINEMENT_PLAN;
    }

    Phase refinementPlan() {
        String rewriteSummary =
                host.planEvolutionAuditor.rewriteSummary(
                        rewriteCount, currentPlan, currentResult, evidenceHistory);
        observations.add(rewriteSummary);
        InterpretationPlan.Step failedStep = host.analysisRefinementCoordinator.repairRootStep(currentPlan, currentResult);
        String repairReason =
                host.analysisRefinementCoordinator.rewriteReason(currentResult, evidenceHistory);
        Map<String, Object> repairEvidenceContext = host.planEvolutionAuditor.repairContext(evidenceHistory);
        metadata.put("latestDagRepairEvidenceContext", repairEvidenceContext);
        boolean dagRepairAttempt = !currentResult.success() || failedStep != null;
        if (dagRepairAttempt) {
            host.planEvolutionAuditor.recordDagRepair(
                    runtimeAttributes,
                    metadata,
                    "STARTED",
                    rewriteCount,
                    repairReason,
                    failedStep,
                    List.of(),
                    null);
        }
        completedTools =
                host.completedWorkflowToolsFromEvents(
                        runtimeAttributes,
                        host.workflowStateTracker.completedToolsFromTraces(traces));
        List<String> pendingRequiredTools =
                host.workflowTools.missingMandatoryTools(
                        host.metadataStringList(metadata, "mandatoryTools"), completedTools);
        List<InterpretationPlanRewriter.RequiredToolExecution> rewriteRequirements =
                new ArrayList<>(
                        host.requiredToolExecutions(
                                pendingRequiredTools,
                                host.metadataStringList(metadata, "requiredToolNames"),
                                host.metadataStringList(metadata, "workflowMandatoryTools")));
        rewriteRequirements.addAll(host.evidenceRefinementRequiredTools(evidenceHistory, tools));
        rewrite =
                rewriter.rewrite(
                        new InterpretationPlanRewriter.RewriteRequest(
                                currentPlan,
                                failedStep,
                                repairReason,
                                observations,
                                tools,
                                host.toolRegistry,
                                rewriteRequirements,
                                evidenceHistory,
                                budgetCaps.configured()
                                        ? new InterpretationPlan.ExecutionPolicy(
                                                budgetCaps.maxSteps(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                budgetCaps.costBudget(),
                                                budgetCaps.latencyBudgetMs(),
                                                null)
                                        : null,
                                AgentRoleAnalysisContext.fromRuntimeAttributes(runtimeAttributes)));
        rewrittenPlan = rewrite.rewrittenPlan();
        InterpretationPlanValidator.ValidationResult rewrittenValidation = rewrite.validation();
        List<String> authoritativeRewritePasses = List.of();
        Map<String, Object> authoritativeRewriteRepair = Map.of();
        rewriteWorkflowDag =
                host.authoritativeWorkflowDagForContinuation(
                        authoritativeWorkflowDag, rewrittenPlan, completedTools);
        if (rewrittenPlan != null && hasAuthoritativeWorkflowDag) {
            InterpretationPlanOptimizer.OptimizationResult authoritativeRewrite =
                    new InterpretationPlanOptimizer(host.toolRegistry)
                            .optimize(rewrittenPlan, rewriteWorkflowDag);
            rewrittenPlan =
                    authoritativeRewrite.plan() == null
                            ? rewrittenPlan
                            : authoritativeRewrite.plan();
            authoritativeRewritePasses = authoritativeRewrite.appliedPasses();
            authoritativeRewriteRepair = authoritativeRewrite.repairResult().auditMetadata();
            rewrittenValidation =
                    validator.validate(
                            rewrittenPlan,
                            host.toolRegistry,
                            new LinkedHashSet<>(tools == null ? List.of() : tools),
                            rewriteWorkflowDag,
                            authoritativeWorkflowTaskId);
        }
        boolean rewrittenValid =
                rewrittenPlan != null && rewrittenValidation != null && rewrittenValidation.valid();
        metadata.put("interpretationPlanRewriteAttempted", true);
        metadata.put("interpretationPlanRewriteCount", rewriteCount);
        metadata.put("interpretationPlanRewriteValid", rewrittenValid);
        metadata.put(
                "interpretationPlanRewriteExecutable",
                rewrittenValidation != null && rewrittenValidation.executable());
        metadata.put("authoritativeWorkflowRewritePasses", authoritativeRewritePasses);
        if (!authoritativeRewriteRepair.isEmpty()) {
            metadata.put("authoritativeWorkflowRewriteRepair", authoritativeRewriteRepair);
        }
        if (!rewrittenValid
                && rewrite.errorMessage() != null
                && !rewrite.errorMessage().isBlank()) {
            metadata.put("interpretationPlanRewriteError", rewrite.errorMessage());
        }
        host.planEvolutionAuditor.recordEvolution(
                currentPlan,
                rewrittenPlan,
                rewriteCount + 1,
                rewrittenValid ? "ACCEPTED" : "REJECTED",
                evidenceHistory,
                runtimeAttributes,
                metadata);
        List<Map<String, Object>> repairChanges =
                rewrittenPlan == null
                        ? List.of()
                        : host.planEvolutionAuditor.changes(currentPlan, rewrittenPlan);
        if (dagRepairAttempt) {
            host.planEvolutionAuditor.recordDagRepair(
                    runtimeAttributes,
                    metadata,
                    rewrittenValid ? "APPLIED" : "REJECTED",
                    rewriteCount,
                    repairReason,
                    failedStep,
                    repairChanges,
                    rewrittenValidation);
        }
        host.runtimeGuard.checkCancelled(cancellationCheck);

        rewriteStage = rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount;
        host.recordInterpretationPlanEvaluation(
                rewriteStage, rewrittenValidation, runtimeAttributes, metadata);
        if (!rewrittenValid) {
            String evaluationError =
                    firstNonBlank(rewrite.errorMessage(), "rewriter did not return a valid plan");
            observations.add(
                    "InterpretationPlan "
                            + rewriteStage
                            + " failed plan evaluation and was not executed: "
                            + evaluationError);
            currentResult =
                    host.planEvaluationFailure(rewriteStage, rewrittenValidation, evaluationError);
            planAttemptResults.add(currentResult);
            return REFINEMENT_GATE;
        }

        if (ToolCallFingerprint.materiallyEquivalent(currentPlan, rewrittenPlan)) {
            duplicateToolPlanSuppressed = true;
            metadata.put("duplicateToolPlanSuppressed", true);
            metadata.put("duplicateToolPlanStage", rewriteStage);
            metadata.put(
                    "duplicateToolPlanFingerprints", ToolCallFingerprint.forPlan(rewrittenPlan));
            observations.add(
                    "InterpretationPlan "
                            + rewriteStage
                            + " was not executed because its tool calls have no material input"
                            + " change from the previous evidence round.");
            return FINALIZE;
        }

        return REFINEMENT_DATA;
    }

    Phase refinementData() {
        completion = host.rejectPlanExceedingRequestBudget(rewrittenPlan, runtimeAttributes, traces, metadata, observations);
        if (completion != null) return END;
        currentPlan = rewrittenPlan;
        host.planSnapshotService.saveGenerated(
                rewriteStage, currentPlan, tenantId, requestId, runtimeAttributes, metadata);
        host.recordInterpretationPlanExecutionStarted(
                rewriteStage, currentPlan, runtimeAttributes, metadata);
        Map<String, Object> rewriteExecutionAttributes =
                host.workflowAttemptAttributes(
                        host.workflowStateTracker.attributesWithCompletedWorkflowState(
                                runtimeAttributes, completedTools, traces),
                        rewriteCount,
                        rewriteWorkflowDag);
        rewriteExecutionAttributes.put(
                "reusablePlanSteps", List.copyOf(reusablePlanSteps.values()));
        InterpretationPlanRuntime.ExecutionRequest rewriteRequest =
                host.planExecutionRequest(
                        currentPlan,
                        tenantId,
                        requestId,
                        conversationId,
                        userId,
                        tools,
                        rewriteExecutionAttributes);
        host.suspendForDurablePlanExecution(
                initialPipelinePlan,
                currentPlan,
                rewriteRequest,
                rewriteCount,
                maxRewriteTimes,
                planAttemptResults,
                evidenceHistory,
                runtimeAttributes,
                traces,
                observations,
                metadata);
        currentResult =
                runtime.execute(
                        rewriteRequest,
                        host.planKernelScope(
                                tenantId,
                                userId,
                                requestId,
                                conversationId,
                                rewriteExecutionAttributes));
        reusablePlanSteps =
                host.analysisRefinementCoordinator.reusableSteps(
                        reusablePlanSteps, currentPlan, currentResult);
        host.recordPlanRuntimeResult(rewriteStage, currentResult, traces, observations, metadata);
        host.planSnapshotService.saveExecution(
                rewriteStage + "_result",
                currentPlan,
                tenantId,
                requestId,
                runtimeAttributes,
                metadata,
                currentResult);
        host.checkCancelledUnlessBatchEvidence(cancellationCheck, currentResult, metadata);
        if (currentResult.approvalRequired()) {
            metadata.put("stopReason", "confirmation_required");
            metadata.put("confirmationRequired", true);
            completion = host.answerFinalizer.finishExecution("", traces, metadata, observations);
            return END;
        }
        currentResult =
                host.consumePlanExecutionResult(
                        rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                        currentPlan,
                        currentResult,
                        runtimeAttributes,
                        observations,
                        metadata);
        planAttemptResults.add(currentResult);

        return REFINEMENT_ANALYSIS;
    }

    Phase refinementAnalysis() {
        currentEvidence =
                host.analyzeInterpretationPlanEvidence(
                        activeChatModel,
                        query,
                        systemPrompt,
                        currentPlan,
                        currentResult,
                        rewriteCount + 1,
                        evidenceHistory,
                        runtimeAttributes,
                        metadata,
                        cancellationCheck);
        latestRecordCoverage =
                host.analyzeClaimAdmissionCoverage(
                        activeChatModel,
                        query,
                        currentResult,
                        planAttemptResults,
                        runtimeAttributes,
                        metadata,
                        cancellationCheck);
        currentEvidence =
                host.semanticClaimCoordinator.evaluate(
                        currentEvidence,
                        latestRecordCoverage.summaryResults(),
                        rewriteCount + 1,
                        runtimeAttributes,
                        metadata);
        evidenceHistory.add(currentEvidence);
        latestAugmentationDecision =
                host.decideEvidenceAugmentation(
                        currentEvidence,
                        currentResult,
                        host.evidenceExplorationAvailable(
                                currentEvidence,
                                currentResult,
                                tools,
                                rewriteCount < maxRewriteTimes),
                        false,
                        metadata);
        host.recordEvidenceAugmentationDecision(
                latestAugmentationDecision, rewriteCount + 1, runtimeAttributes, metadata);
        int revisedEvidenceLimit =
                host.evidenceDrivenRewriteLimit(
                        configuredMaxRewriteTimes,
                        latestAugmentationDecision,
                        evidenceHistory,
                        tools);
        if (revisedEvidenceLimit > maxRewriteTimes) {
            maxRewriteTimes = revisedEvidenceLimit;
            metadata.put("evidenceDrivenRewriteBudgetApplied", true);
            metadata.put("evidenceDrivenRewriteBudgetExpandedAfterDiscovery", true);
            metadata.put("interpretationPlanMaxRewriteTimes", maxRewriteTimes);
            metadata.put(
                    "evidenceDrivenRewriteBudgetReason",
                    "A completed discovery step exposed an available execution tool for the"
                        + " remaining evidence gap.");
            observations.add(
                    "InterpretationPlan retained another bounded evidence round because discovery"
                        + " returned an actionable tool that is present in the pinned runtime"
                        + " registry.");
        }
        if ("DAG_NO_PROGRESS".equals(currentResult.status())) {
            usablePartialAnalysis =
                    evidenceHistory.stream().anyMatch(host::usableEvidenceAvailable);
            metadata.put("interpretationPlanNoProgressStopped", true);
            metadata.put(
                    "interpretationPlanNoProgressStage",
                    rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount);
            observations.add(
                    "InterpretationPlan stopped after a rewritten DAG made no execution progress;"
                        + " the persisted evidence chain will be synthesized without another"
                        + " unchanged rewrite.");
            return FINALIZE;
        }
        if (latestAugmentationDecision.decision()
                == EvidenceAugmentationPolicy.Decision.ANALYZE_WITH_LIMITATIONS) {
            usablePartialAnalysis = host.usableEvidenceAvailable(currentEvidence);
            return FINALIZE;
        }

        return latestAugmentationDecision.decision() == EvidenceAugmentationPolicy.Decision.COMPLETE
                ? FINAL_REFINED
                : REFINEMENT_GATE;
    }

    Phase finalInitial() {
        host.recordEvidenceStopState(metadata, firstEvidence, "evidence_sufficient", 1);
        host.recordMandatoryWorkflowCompletion(traces, metadata, runtimeAttributes);
        String synthesizedAnswer =
                host.synthesizeInterpretationPlanAnswer(
                        activeChatModel,
                        query,
                        systemPrompt,
                        firstResult,
                        planAttemptResults,
                        runtimeAttributes,
                        observations,
                        metadata,
                        cancellationCheck,
                        "initial",
                        latestRecordCoverage);
        completion =
                host.finishSynthesizedInterpretationPlanAnswer(
                        activeChatModel,
                        query,
                        systemPrompt,
                        traces,
                        metadata,
                        observations,
                        synthesizedAnswer,
                        cancellationCheck,
                        "evidence_sufficient");
        return END;
    }

    Phase finalRefined() {
        host.recordEvidenceStopState(
                metadata, currentEvidence, "evidence_sufficient", rewriteCount + 1);
        host.recordMandatoryWorkflowCompletion(traces, metadata, runtimeAttributes);
        String synthesizedAnswer =
                host.synthesizeInterpretationPlanAnswer(
                        activeChatModel,
                        query,
                        systemPrompt,
                        currentResult,
                        planAttemptResults,
                        runtimeAttributes,
                        observations,
                        metadata,
                        cancellationCheck,
                        rewriteCount == 1 ? "rewrite" : "rewrite" + rewriteCount,
                        latestRecordCoverage);
        completion =
                host.finishSynthesizedInterpretationPlanAnswer(
                        activeChatModel,
                        query,
                        systemPrompt,
                        traces,
                        metadata,
                        observations,
                        synthesizedAnswer,
                        cancellationCheck,
                        "evidence_sufficient");
        return END;
    }

    Phase finalizeAnalysis() {
        if (!usablePartialAnalysis
                && evidenceHistory.stream().anyMatch(host::usableEvidenceAvailable)) {
            usablePartialAnalysis = true;
            latestAugmentationDecision =
                    host.evidenceAugmentationPolicy.decide(
                            new EvidenceAugmentationPolicy.Context(
                                    true,
                                    false,
                                    true,
                                    false,
                                    false,
                                    host.taskEvidenceRequirement(metadata)));
            host.recordEvidenceAugmentationDecision(
                    latestAugmentationDecision,
                    evidenceHistory.size(),
                    runtimeAttributes,
                    metadata);
        }
        metadata.put(
                "interpretationPlanRewriteBudgetExceeded",
                !usablePartialAnalysis
                        && !duplicateToolPlanSuppressed
                        && (maxRewriteTimes <= 0
                                || firstInteger(metadata.get("interpretationPlanRewriteCount"), 0)
                                        >= maxRewriteTimes));
        metadata.put(
                "interpretationPlanFallbackMode",
                host.planEvolutionAuditor.fallbackMode(initialPipelinePlan));
        String evidenceCompletionReason =
                usablePartialAnalysis
                        ? "evidence_partial_analysis"
                        : duplicateToolPlanSuppressed
                                ? "duplicate_tool_plan_suppressed"
                                : "evidence_iteration_limit";
        metadata.put("stopReason", evidenceCompletionReason);
        metadata.put("interpretationPlanEvidenceIterationCount", evidenceHistory.size());
        if (!evidenceHistory.isEmpty()) {
            host.recordEvidenceStopState(
                    metadata,
                    evidenceHistory.get(evidenceHistory.size() - 1),
                    evidenceCompletionReason,
                    evidenceHistory.size());
        }
        observations.add(
                usablePartialAnalysis
                        ? "InterpretationPlan has usable evidence and will produce a stage analysis"
                              + " with explicit limitations."
                        : duplicateToolPlanSuppressed
                                ? "InterpretationPlan stopped before a duplicate tool call; final"
                                      + " answer will use the persisted evidence chain."
                                : "InterpretationPlan completed its evidence revision budget; final"
                                      + " answer will reconcile all persisted evidence and"
                                      + " unresolved gaps.");
        host.runMissingMandatoryWorkflowTools(
                activeChatModel,
                traces,
                observations,
                query,
                conversationId,
                requestId,
                userId,
                tenantId,
                tools,
                host.metadataStringList(metadata, "mandatoryTools"),
                documentIds,
                documentTags,
                webSearchResultLimit,
                metadata,
                runtimeAttributes,
                maxToolCalls,
                systemPrompt,
                cancellationCheck);
        if (Boolean.TRUE.equals(metadata.get("confirmationRequired"))) {
            completion = host.answerFinalizer.finishExecution("", traces, metadata, observations);
            return END;
        }
        if (Boolean.TRUE.equals(metadata.get("toolBudgetExceeded"))) {
            completion =
                    host.answerFinalizer.finishBudgetedSummary(
                            activeChatModel,
                            query,
                            systemPrompt,
                            traces,
                            metadata,
                            observations,
                            cancellationCheck);
            return END;
        }
        AgentOrchestrator.AgentExecutionResult blockedResult =
                host.finishMandatoryWorkflowBlockedIfPending(
                        activeChatModel,
                        query,
                        systemPrompt,
                        traces,
                        metadata,
                        observations,
                        runtimeAttributes,
                        cancellationCheck,
                        "mandatory_workflow_incomplete",
                        "InterpretationPlan failed and mandatory workflow tools are still"
                            + " incomplete.");
        if (blockedResult != null) {
            completion = blockedResult;
            return END;
        }
        AgentOrchestrator.AgentExecutionResult planWorkflowBlockedResult =
                host.finishInterpretationPlanWorkflowBlockedIfPending(
                        traces,
                        metadata,
                        observations,
                        "interpretation_plan_workflow_incomplete",
                        "InterpretationPlan workflow guard blocked final_answer before all required"
                            + " DAG steps completed.");
        if (planWorkflowBlockedResult != null) {
            completion = planWorkflowBlockedResult;
            return END;
        }
        if (!planAttemptResults.isEmpty()) {
            planAttemptResults.addAll(
                    RecoveredBatchEvidenceBridge.project(traces, host.objectMapper));
            String synthesisStage =
                    host.terminalSynthesisStage(
                            usablePartialAnalysis,
                            duplicateToolPlanSuppressed,
                            Boolean.TRUE.equals(
                                    metadata.get("mandatoryWorkflowRecoveredAfterPlan")));
            String synthesizedAnswer =
                    host.synthesizeInterpretationPlanAnswer(
                            activeChatModel,
                            query,
                            systemPrompt,
                            planAttemptResults.get(planAttemptResults.size() - 1),
                            planAttemptResults,
                            runtimeAttributes,
                            observations,
                            metadata,
                            cancellationCheck,
                            synthesisStage,
                            latestRecordCoverage);
            completion =
                    host.finishSynthesizedInterpretationPlanAnswer(
                            activeChatModel,
                            query,
                            systemPrompt,
                            traces,
                            metadata,
                            observations,
                            synthesizedAnswer,
                            cancellationCheck,
                            evidenceCompletionReason);
            return END;
        }
        completion =
                host.answerFinalizer.finishReviewedSummary(
                        activeChatModel,
                        query,
                        systemPrompt,
                        traces,
                        metadata,
                        observations,
                        cancellationCheck,
                        "interpretation_plan_failed");
        return END;
    }
    Phase execute(Phase phase) {
        return switch (phase) {
            case PREPARE -> prepare();
            case INITIAL_DATA -> initialData();
            case INITIAL_ANALYSIS -> initialAnalysis();
            case PREPARE_REFINEMENT -> prepareRefinement();
            case REFINEMENT_GATE -> refinementGate();
            case REFINEMENT_PLAN -> refinementPlan();
            case REFINEMENT_DATA -> refinementData();
            case REFINEMENT_ANALYSIS -> refinementAnalysis();
            case FINAL_INITIAL -> finalInitial();
            case FINAL_REFINED -> finalRefined();
            case FINALIZE -> finalizeAnalysis();
            case END -> throw new IllegalStateException("END is not an executable node");
        };
    }
}
