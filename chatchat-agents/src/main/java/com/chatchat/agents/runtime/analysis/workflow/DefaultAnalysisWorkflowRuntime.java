package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.AdaptiveAnalysisController;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisExecutionMode;
import com.chatchat.common.runtime.analysis.routing.AnalysisWorkflowRouter;
import com.chatchat.common.runtime.analysis.routing.StandardAnalysisQueryAnalyzer;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.common.runtime.analysis.spi.AnalysisEvidenceArchivePort;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;
import com.chatchat.common.runtime.analysis.spi.AnalysisProgressPort;
import com.chatchat.common.runtime.analysis.spi.EvidenceRecoveryWorkflow;

import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceRecoveryResult;
import com.chatchat.common.runtime.analysis.recovery.RecoveryStatus;
import com.chatchat.common.runtime.analysis.routing.AnalysisRuntimePath;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;

import com.chatchat.common.runtime.workflow.WorkflowExecutionContext;
import com.chatchat.common.runtime.workflow.WorkflowHandle;
import com.chatchat.common.runtime.workflow.WorkflowRuntime;
import com.chatchat.common.runtime.workflow.WorkflowStartRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Agent Runtime entry point for all problem-analysis workflows. */
@Service
public class DefaultAnalysisWorkflowRuntime implements AnalysisRuntimePort {
    public static final String WORKFLOW_TYPE = "problem-analysis-v1";

    private final AnalysisWorkflowRouter router;
    private final Supplier<WorkflowRuntime> workflowRuntime;
    private final Supplier<AnalysisEvidenceArchivePort> evidenceArchive;
    private final List<EvidenceRecoveryWorkflow> evidenceRecoveryWorkflows;
    private final EvidenceStateInspector evidenceInspector = new EvidenceStateInspector();
    private final AdaptiveAnalysisController adaptiveController = new AdaptiveAnalysisController();
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private AnalysisProgressPort progressPort;

    @Autowired
    public void setProgressPort(ObjectProvider<AnalysisProgressPort> provider) {
        this.progressPort = provider.getIfAvailable();
    }

    public DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows) {
        this(workflows, () -> null, () -> null, List.of());
    }

    public DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows, WorkflowRuntime workflowRuntime) {
        this(workflows, () -> workflowRuntime, () -> null, List.of());
    }

    DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows, WorkflowRuntime workflowRuntime,
                                   AnalysisEvidenceArchivePort archive) {
        this(workflows, () -> workflowRuntime, () -> archive, List.of());
    }

    DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows, WorkflowRuntime workflowRuntime,
                                   AnalysisEvidenceArchivePort archive,
                                   List<EvidenceRecoveryWorkflow> evidenceRecoveryWorkflows) {
        this(workflows, () -> workflowRuntime, () -> archive, evidenceRecoveryWorkflows);
    }

    @Autowired
    public DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows,
                                          ObjectProvider<WorkflowRuntime> workflowRuntime,
                                          ObjectProvider<AnalysisEvidenceArchivePort> evidenceArchive,
                                          ObjectProvider<EvidenceRecoveryWorkflow> evidenceRecoveryWorkflows) {
        this(workflows, workflowRuntime::getIfAvailable, evidenceArchive::getIfAvailable,
            evidenceRecoveryWorkflows.orderedStream().toList());
    }

    private DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows,
                                           Supplier<WorkflowRuntime> workflowRuntime,
                                           Supplier<AnalysisEvidenceArchivePort> evidenceArchive,
                                           List<EvidenceRecoveryWorkflow> evidenceRecoveryWorkflows) {
        this.router = new AnalysisWorkflowRouter(new StandardAnalysisQueryAnalyzer(), workflows);
        this.workflowRuntime = workflowRuntime;
        this.evidenceArchive = evidenceArchive;
        this.evidenceRecoveryWorkflows = evidenceRecoveryWorkflows == null
            ? List.of() : List.copyOf(evidenceRecoveryWorkflows);
    }

    @Override
    public AnalysisExecutionOutcome analyze(AnalysisContext context) {
        if (context.executionMode() == AnalysisExecutionMode.DURABLE) {
            return executeDurably(context);
        }
        return archive(context, withRuntimeMetadata(executeInline(context), AnalysisExecutionMode.INLINE, null));
    }

    private AnalysisExecutionOutcome archive(AnalysisContext context, AnalysisExecutionOutcome outcome) {
        if ((!com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.active(outcome.metadata())
            && (outcome.verification() == null || !outcome.verification().accepted()))
            || outcome.evidenceBundle().evidence().isEmpty()) return outcome;
        if (context.kernelScope().tenantId() == null || context.kernelScope().userId() == null)
            return outcome;
        try {
            AnalysisEvidenceArchivePort store = evidenceArchive.get();
            if (store == null) throw new IllegalStateException("Evidence archive unavailable");
            Map<String, Object> bundleMetadata = new LinkedHashMap<>(outcome.evidenceBundle().metadata());
            for (String key : List.of("modelEvidenceAssessmentAudit", "modelEvidenceAssessmentHistory", "analysisExecutionOutcome", "modelAnalysisProtocol", "modelDecision",
                "executionState", "executionStopReason", "publicationState", "modelPublicationRequest", "modelEvidenceSnapshotRef")) {
                if (outcome.metadata().containsKey(key)) bundleMetadata.put(key, outcome.metadata().get(key));
            }
            EvidenceBundle archived = new EvidenceBundle(null, outcome.evidenceBundle().evidence(),
                outcome.evidenceBundle().limitations(), bundleMetadata);
            AnalysisEvidenceArchivePort.Reference reference = store.archive(context, archived);
            Map<String, Object> metadata = new LinkedHashMap<>(outcome.metadata());
            metadata.put("evidenceArchiveId", reference.archiveId());
            metadata.put("evidenceSha256", reference.sha256());
            metadata.put("evidenceByteLength", reference.byteLength());
            return new AnalysisExecutionOutcome(outcome.schemaVersion(), outcome.workflowType(), outcome.plan(),
                outcome.verification(), archived, outcome.synthesis(), metadata);
        } catch (RuntimeException failure) {
            if (failure instanceof java.util.concurrent.CancellationException
                || Thread.currentThread().isInterrupted()) throw failure;
            Map<String, Object> metadata = new LinkedHashMap<>(outcome.metadata());
            metadata.put("evidenceArchiveStatus", "FAILED");
            return new AnalysisExecutionOutcome(outcome.schemaVersion(), outcome.workflowType(), outcome.plan(),
                outcome.verification(), outcome.evidenceBundle(), outcome.synthesis(), metadata);
        }
    }

    private AnalysisExecutionOutcome executeInline(AnalysisContext context) {
        AnalysisWorkflowRouter.RoutedWorkflow routed = router.route(context);
        AnalysisProgressPort journal = progress(context);
        if (journal != null) {
            var reservation = journal.start(context.kernelScope(), recoveryMaxRounds(context) + 1);
            if (!reservation.admitted())
                return new AnalysisExecutionOutcome(null, routed.workflow().type(), null,
                    new VerificationResult(false, List.of(), List.of(reservation.reason())),
                    EvidenceBundle.empty(reservation.reason()), "", Map.of(
                        "adaptiveAnalysisAction", "STOP", "adaptiveAnalysisReason", reservation.reason(),
                        "adaptiveAnalysisRounds", reservation.rounds(), "adaptiveAnalysisMaxRounds", reservation.maxRounds(),
                        "runtimeEvidenceRevision", reservation.revision(), "runtimeProgressPersistence", "DATABASE"));
        }
        try {
            return executeClaimed(routed, journal);
        } catch (RuntimeException failure) {
            if (journal != null) journal.stop(context.kernelScope(),
                failure instanceof java.util.concurrent.CancellationException ? "CANCELLED" : "EXECUTION_FAILED");
            throw failure;
        }
    }

    private AnalysisProgressPort progress(AnalysisContext context) {
        return context.kernelScope().tenantId() != null && context.kernelScope().userId() != null ? progressPort : null;
    }

    private AnalysisExecutionOutcome executeClaimed(AnalysisWorkflowRouter.RoutedWorkflow routed,
                                                   AnalysisProgressPort journal) {
        AnalysisExecutionOutcome primary = routed.workflow().execute(
            routed.context(), routed.context().kernelScope());
        if (routed.workflow() instanceof com.chatchat.common.runtime.analysis.asset.AssetGuidancePlanningWorkflow guidance) {
            var templates = guidance.resolveTemplate(routed.context(), primary);
            var request = guidance.requestData(routed.context(), primary, templates);
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var data = guidance.acquireData(routed.context(), request, templates);
            primary = guidance.synthesize(routed.context(), request, data);
        }
        AnalysisExecutionOutcome result = recoverAndContinue(routed.context(), routed.workflow(), primary);
        if (journal != null) {
            if (primary.workflowType() == com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType.ASSET_GUIDANCE)
                journal.observe(routed.context(), result, List.of(), 1, new AdaptiveAnalysisController.Decision(
                    AdaptiveAnalysisController.Action.STOP, "GUIDANCE_COMPLETE"));
            else if (!"DELIVER".equals(result.metadata().get("adaptiveAnalysisAction")))
                journal.stop(routed.context().kernelScope(), String.valueOf(result.metadata().get("adaptiveAnalysisReason")));
            var state = journal.state(routed.context().kernelScope()).orElseThrow();
            Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
            metadata.put("runtimeEvidenceRevision", state.revision());
            metadata.put("runtimeConsumedEvidenceRevision", state.consumedRevision());
            metadata.put("runtimeProgressPersistence", "DATABASE");
            metadata.put("adaptiveAnalysisRounds", state.rounds());
            metadata.put("runtimeRunId", routed.context().kernelScope().runId() == null
                ? routed.context().kernelScope().requestId() : routed.context().kernelScope().runId());
            result = outcome(result, result.verification(), result.evidenceBundle(), metadata);
        }
        return result;
    }

    private AnalysisExecutionOutcome recoverAndContinue(AnalysisContext context, AnalysisWorkflow workflow,
                                                         AnalysisExecutionOutcome primary) {
        if (com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.active(primary.metadata()))
            return continueModelDirected(context, workflow, primary);
        // Missing template metadata is not a request to acquire or analyze business data.
        if (primary.workflowType() == com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType.ASSET_GUIDANCE)
            return completeAssetGuidance(context, workflow, primary);
        EvidenceBundle current = primary.evidenceBundle();
        EvidenceStateInspector.State state = evidenceInspector.inspect(current, primary.metadata());
        AnalysisExecutionOutcome analyzed = primary;
        List<EvidenceGap> gaps = analysisGaps(context, workflow, analyzed, state, List.of());
        Map<String, Object> metadata = new LinkedHashMap<>(primary.metadata());
        Map<String, Object> acquisitionMetadata = new LinkedHashMap<>(primary.metadata());
        metadata.put("runtimePrimaryPath", (primary.workflowType() == com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType.DOCUMENT
            ? AnalysisRuntimePath.DOCUMENT_RETRIEVAL : AnalysisRuntimePath.PRIMARY_ANALYSIS).name());
        List<Map<String, Object>> trace = new java.util.ArrayList<>();
        String status = "NOT_NEEDED";
        int maxRounds = recoveryMaxRounds(context);
        var decision = adaptiveController.decide(feedback(analyzed, gaps, true, false, false), 0, maxRounds);
        AnalysisProgressPort journal = progress(context);
        long revision = journal == null ? 0 : journal.observe(context, analyzed, gaps, 1, decision).revision();
        boolean continuedThisRound = false;
        for (int round = 1; decision.action() == AdaptiveAnalysisController.Action.RECOVER; round++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            EvidenceGap issue = null;
            EvidenceRecoveryWorkflow recovery = null;
            try {
                for (EvidenceGap candidate : gaps) {
                    recovery = selectRecovery(context, candidate);
                    if (recovery != null) {
                        issue = candidate;
                        break;
                    }
                }
            } catch (java.util.concurrent.CancellationException cancellation) {
                throw cancellation;
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                trace.add(Map.of("round", round, "stage", "SELECTION", "status", "FAILED",
                    "error", safe(failure.getMessage())));
                status = "FAILED";
                break;
            }
            if (recovery == null) {
                status = "NO_WORKFLOW";
                break;
            }
            if (journal != null) {
                var wake = journal.reserveRecovery(context.kernelScope(), revision);
                if (!wake.admitted()) {
                    decision = new AdaptiveAnalysisController.Decision(AdaptiveAnalysisController.Action.STOP, wake.reason());
                    status = wake.reason();
                    break;
                }
            }
            EvidenceRecoveryResult recovered;
            try {
                recovered = java.util.Objects.requireNonNull(
                    recovery.recover(context, current, issue, round), "Recovery returned no result");
            } catch (java.util.concurrent.CancellationException cancellation) {
                throw cancellation;
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                trace.add(Map.of("round", round, "gap", issue.reason().name(), "status", "FAILED",
                    "workflow", recovery.getClass().getSimpleName(), "error", safe(failure.getMessage())));
                status = "FAILED";
                break;
            }
            EvidenceBundle previous = current;
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            current = mergeEvidence(current, recovered.evidence());
            trace.add(Map.of("round", round, "gap", issue.reason().name(),
                "status", recovered.status().name(), "workflow", recovery.getClass().getSimpleName(),
                "evidenceCount", current.evidence().size()));
            acquisitionMetadata.putAll(recovered.metadata());
            state = evidenceInspector.inspect(current, acquisitionMetadata);
            metadata.put("evidenceRecoveryTrace", List.copyOf(trace));
            metadata.put("evidenceRecoveryStatus", recovered.status().name());
            metadata.put("evidenceState", state.projection());
            metadata.put("runtimeRoute", "CONTINUE_ANALYSIS");
            analyzed = workflow.continueAfterRecovery(context, analyzed, current, Map.copyOf(metadata));
            continuedThisRound = true;
            current = analyzed.evidenceBundle();
            acquisitionMetadata.putAll(analyzed.metadata());
            state = evidenceInspector.inspect(current, acquisitionMetadata);
            List<EvidenceGap> nextGaps = analysisGaps(context, workflow, analyzed, state, recovered.remainingGaps());
            boolean progress = !current.evidence().equals(previous.evidence()) || !nextGaps.equals(gaps);
            gaps = nextGaps;
            decision = adaptiveController.decide(feedback(analyzed, gaps, progress,
                recovered.status() == RecoveryStatus.FAILED, recovered.status() == RecoveryStatus.EXHAUSTED),
                round, maxRounds);
            if (journal != null) {
                var persisted = journal.observe(context, analyzed, gaps, round + 1, decision);
                revision = persisted.revision();
                decision = new AdaptiveAnalysisController.Decision(
                    AdaptiveAnalysisController.Action.valueOf(persisted.action()), persisted.reason());
            }
            Map<String, Object> observed = new LinkedHashMap<>(trace.get(trace.size() - 1));
            observed.put("decision", decision.action().name());
            observed.put("reason", decision.reason());
            observed.put("remainingGapReasons", gaps.stream().map(gap -> gap.reason().name()).toList());
            observed.put("verificationAccepted", analyzed.verification() != null && analyzed.verification().accepted());
            trace.set(trace.size() - 1, Map.copyOf(observed));
            status = recovered.status() == RecoveryStatus.FAILED || recovered.status() == RecoveryStatus.EXHAUSTED
                ? recovered.status().name() : gaps.isEmpty() ? "COMPLETE" : decision.reason();
        }
        if (trace.isEmpty() && !gaps.isEmpty() && !"NO_WORKFLOW".equals(status) && !"FAILED".equals(status))
            status = decision.reason();
        metadata.put("evidenceState", state.projection());
        metadata.put("evidenceRecoveryStatus", status);
        metadata.put("evidenceRecoveryTrace", List.copyOf(trace));
        metadata.put("adaptiveAnalysisSchema", "adaptive_analysis_decision.v1");
        metadata.put("adaptiveAnalysisAction", "NO_WORKFLOW".equals(status) || "FAILED".equals(status)
            ? "STOP" : decision.action().name());
        metadata.put("adaptiveAnalysisReason", "NO_WORKFLOW".equals(status) || "FAILED".equals(status)
            ? status : decision.reason());
        metadata.put("adaptiveAnalysisMaxRounds", maxRounds + 1);
        metadata.put("adaptiveAnalysisRounds", trace.stream().filter(item -> !"SELECTION".equals(item.get("stage"))).count() + 1);
        metadata.put("adaptiveAnalysisGapReasons", gaps.stream().map(gap -> gap.reason().name()).toList());
        metadata.put("runtimeRoute", "CONTINUE_ANALYSIS");
        if (gaps.isEmpty() && trace.isEmpty()) {
            return outcome(analyzed, analyzed.verification(), current, metadata);
        }
        // The analysis workflow owns verification and synthesis, including when recovery failed.
        AnalysisExecutionOutcome continued = continuedThisRound ? analyzed
            : workflow.continueAfterRecovery(context, analyzed, current, Map.copyOf(metadata));
        Map<String, Object> combined = new LinkedHashMap<>(continued.metadata());
        combined.putAll(metadata);
        return outcome(continued, continued.verification(), continued.evidenceBundle(), combined);
    }

    private AnalysisExecutionOutcome continueModelDirected(AnalysisContext context, AnalysisWorkflow workflow,
                                                            AnalysisExecutionOutcome primary) {
        var current = primary;
        int budget = recoveryMaxRounds(context);
        var journal = progress(context);
        for (int round = 0; ; round++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var decision = adaptiveController.decideModel(current.metadata(), round, budget);
            // Only the workflow/model's explicit requests can initiate acquisition. Inspector gaps are observations.
            var requests = workflow.recoveryRequests(context, current);
            if (Boolean.TRUE.equals(current.metadata().get("confirmationRequired"))
                || Boolean.TRUE.equals(current.metadata().get("fatalExecutionBlocked")))
                decision = new AdaptiveAnalysisController.Decision(AdaptiveAnalysisController.Action.STOP, "GOVERNANCE_REJECTION");
            var metadata = new LinkedHashMap<String,Object>(current.metadata());
            metadata.put("adaptiveAnalysisAction", decision.action().name());
            metadata.put("adaptiveAnalysisReason", decision.reason());
            metadata.put("decisionAuthority", "MODEL");
            metadata.put("executionState", decision.action() == AdaptiveAnalysisController.Action.RECOVER ? "RUNNING" : "COMPLETED");
            metadata.put("executionStopReason", "BUDGET_EXHAUSTED".equals(decision.reason()) ? "RESOURCE_BUDGET_EXHAUSTED"
                : com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.action(metadata)
                    == com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.Action.WAIT ? "WAIT_UNSUPPORTED" : "MODEL_DECISION");
            current = outcome(current, current.verification(), current.evidenceBundle(), metadata);
            long revision = 0;
            if (journal != null) {
                var persisted = journal.observe(context, current, requests, round + 1, decision);
                revision = persisted.revision();
                // Respect persisted execution admission (budget/lease), never infer business completion.
                if (!persisted.action().equals(decision.action().name()))
                    decision = new AdaptiveAnalysisController.Decision(
                        AdaptiveAnalysisController.Action.valueOf(persisted.action()), persisted.reason());
            }
            if (decision.action() != AdaptiveAnalysisController.Action.RECOVER) {
                metadata.put("adaptiveAnalysisAction", decision.action().name());
                metadata.put("adaptiveAnalysisReason", decision.reason());
                boolean denied = "GOVERNANCE_REJECTION".equals(decision.reason());
                boolean publish = !denied && decision.action() == AdaptiveAnalysisController.Action.DELIVER
                    && com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.publishRequested(metadata);
                if (denied) metadata.put("executionStopReason", "GOVERNANCE_REJECTION");
                if ("BUDGET_EXHAUSTED".equals(decision.reason())) metadata.put("executionStopReason", "RESOURCE_BUDGET_EXHAUSTED");
                if (denied || "BUDGET_EXHAUSTED".equals(decision.reason())
                    || com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.action(metadata)
                        == com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.Action.WAIT)
                    metadata.put("executionState", "STOPPED");
                metadata.put("modelNativeReportDraft", current.synthesis());
                if (publish) {
                    Object raw = metadata.get("modelPublicationRequest");
                    if (!(raw instanceof Map<?,?> binding)
                        || !(metadata.get("modelEvidenceSnapshotRef") instanceof String snapshot) || snapshot.isBlank()
                        || !com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex(current.synthesis()).equals(binding.get("reportSha256"))
                        || !java.util.Objects.equals(metadata.get("modelEvidenceSnapshotRef"), binding.get("evidenceSnapshotRef")))
                    {
                        metadata.put("publicationState", "REJECTED");
                        metadata.put("executionState", "STOPPED");
                        metadata.put("executionStopReason", "GOVERNANCE_REJECTION");
                        throw new IllegalArgumentException("Publication version binding is required");
                    }
                    metadata.put("publicationState", "DELIVERED");
                } else metadata.put("publicationState", denied ? "REJECTED" : "NOT_REQUESTED");
                return new AnalysisExecutionOutcome(null, current.workflowType(), current.plan(), current.verification(),
                    current.evidenceBundle(), publish ? current.synthesis() : "", metadata);
            }
            if (journal != null) {
                var reservation = journal.reserveRecovery(context.kernelScope(), revision);
                if (!reservation.admitted()) {
                    metadata.put("executionState", "STOPPED");
                    metadata.put("executionStopReason", "BUDGET_EXHAUSTED".equals(reservation.reason())
                        ? "RESOURCE_BUDGET_EXHAUSTED" : "EXECUTION_ADMISSION_REJECTED");
                    metadata.put("adaptiveAnalysisAction", "STOP");
                    metadata.put("adaptiveAnalysisReason", reservation.reason());
                    metadata.put("publicationState", "NOT_REQUESTED");
                    metadata.put("modelNativeReportDraft", current.synthesis());
                    return new AnalysisExecutionOutcome(null, current.workflowType(), current.plan(), current.verification(),
                        current.evidenceBundle(), "", metadata);
                }
            }
            var evidence = current.evidenceBundle();
            var receipts = new java.util.ArrayList<Map<String,Object>>();
            for (var request : requests) {
                var recovery = selectRecovery(context, request);
                if (recovery == null) { receipts.add(Map.of("status", "NO_AUTHORIZED_RECOVERY_WORKFLOW")); continue; }
                try {
                    var recovered = recovery.recover(context, evidence, request, round + 1);
                    evidence = mergeEvidence(evidence, recovered.evidence());
                    receipts.add(Map.of("status", recovered.status().name(), "metadata", recovered.metadata()));
                } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted()) throw failure;
                    receipts.add(Map.of("status", "TOOL_FAILED", "reason", safe(failure.getMessage())));
                }
            }
            metadata.put("modelRecoveryReceipts", List.copyOf(receipts));
            current = workflow.continueAfterRecovery(context, current, evidence, Map.copyOf(metadata));
            if (!com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent.active(current.metadata()))
                throw new IllegalArgumentException("Model continuation cannot downgrade its intent protocol");
        }
    }
    private EvidenceBundle mergeEvidence(EvidenceBundle previous, EvidenceBundle recovered) {
        // Deduplicate exact copies only. Two different observations with the same ID must both
        // reach analysis; Runtime cannot choose which content is authoritative.
        var items = new java.util.LinkedHashSet<>(previous.evidence());
        items.addAll(recovered.evidence());
        java.util.Set<String> limitations = new java.util.LinkedHashSet<>(previous.limitations());
        limitations.addAll(recovered.limitations());
        Map<String, Object> metadata = new LinkedHashMap<>(previous.metadata());
        metadata.putAll(recovered.metadata());
        return new EvidenceBundle(null, List.copyOf(items), List.copyOf(limitations), metadata);
    }

    private EvidenceRecoveryWorkflow selectRecovery(AnalysisContext context, EvidenceGap gap) {
        return evidenceRecoveryWorkflows.stream()
            .filter(workflow -> workflow.supports(context, gap))
            .max(java.util.Comparator.comparingInt(EvidenceRecoveryWorkflow::priority))
            .orElse(null);
    }

    private int recoveryMaxRounds(AnalysisContext context) {
        Object value = context.attributes().get("evidenceRecoveryMaxRounds");
        int resolved = value instanceof Number number ? number.intValue() : 2;
        return AdaptiveAnalysisController.boundedRecoveryRounds(resolved);
    }

    private List<EvidenceGap> analysisGaps(AnalysisContext context, AnalysisWorkflow workflow,
            AnalysisExecutionOutcome outcome, EvidenceStateInspector.State state, List<EvidenceGap> remaining) {
        var gaps = new java.util.LinkedHashSet<>(state.issues());
        gaps.addAll(workflow.recoveryRequests(context, outcome));
        gaps.addAll(remaining);
        return List.copyOf(gaps);
    }

    private AdaptiveAnalysisController.Feedback feedback(
            AnalysisExecutionOutcome outcome, List<EvidenceGap> gaps, boolean progress, boolean failed, boolean exhausted) {
        return new AdaptiveAnalysisController.Feedback(gaps,
            outcome.verification() != null && outcome.verification().accepted()
                && !outcome.verification().acceptedEvidence().isEmpty()
                && !"PRIMARY_ANALYSIS_ONLY".equals(outcome.metadata().get("synthesisEvidenceScope")),
            progress, failed, exhausted);
    }

    private AnalysisExecutionOutcome outcome(AnalysisExecutionOutcome source,
                                             VerificationResult verification,
                                             EvidenceBundle evidence,
                                             Map<String, Object> metadata) {
        if (metadata.containsKey("adaptiveAnalysisAction")) {
            Map<String, Object> audit = new LinkedHashMap<>();
            for (String key : List.of("adaptiveAnalysisSchema", "adaptiveAnalysisAction", "adaptiveAnalysisReason",
                    "adaptiveAnalysisMaxRounds", "adaptiveAnalysisRounds", "adaptiveAnalysisGapReasons",
                    "evidenceRecoveryTrace", "runtimeEvidenceRevision", "runtimeConsumedEvidenceRevision",
                    "runtimeProgressPersistence", "runtimeRunId")) {
                if (metadata.containsKey(key)) audit.put(key, metadata.get(key));
            }
            audit.put("workflowType", source.workflowType().name());
            if (source.plan() != null) audit.put("planId", source.plan().planId());
            audit.put("verificationAccepted", verification != null && verification.accepted());
            audit.put("evidenceIds", evidence.evidence().stream().map(item -> item.evidenceId()).toList());
            Map<String, Object> bundleMetadata = new LinkedHashMap<>(evidence.metadata());
            bundleMetadata.put("runtimeAnalysisAudit", Map.copyOf(audit));
            evidence = new EvidenceBundle(evidence.schemaVersion(), evidence.evidence(), evidence.limitations(), bundleMetadata);
        }
        return new AnalysisExecutionOutcome(source.schemaVersion(), source.workflowType(), source.plan(),
            verification, evidence, source.synthesis(), metadata);
    }

    private String safe(String value) { return value == null ? "unknown" : value; }

    private AnalysisExecutionOutcome executeDurably(AnalysisContext context) {
        WorkflowRuntime runtime = workflowRuntime.get();
        if (runtime == null) {
            throw new IllegalStateException("DURABLE analysis requires a WorkflowRuntime");
        }
        register(runtime);
        String workflowId = workflowId(context);
        WorkflowHandle<AnalysisExecutionOutcome> handle = runtime.start(new WorkflowStartRequest<>(
            workflowId, WORKFLOW_TYPE, context.kernelScope().tenantId(), workflowId, context));
        try {
            return withRuntimeMetadata(handle.completion().join(), AnalysisExecutionMode.DURABLE, workflowId);
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw failure;
        }
    }

    private void register(WorkflowRuntime runtime) {
        if (!registered.compareAndSet(false, true)) return;
        runtime.register(WORKFLOW_TYPE, AnalysisContext.class, AnalysisExecutionOutcome.class,
            this::executeRegistered);
    }

    private AnalysisExecutionOutcome executeRegistered(AnalysisContext context,
                                                       WorkflowExecutionContext execution) {
        execution.checkCancellation();
        AnalysisExecutionOutcome outcome = archive(context, executeInline(context));
        execution.checkCancellation();
        return outcome;
    }

    private String workflowId(AnalysisContext context) {
        String request = context.kernelScope().runId() == null
            ? context.kernelScope().requestId() : context.kernelScope().runId();
        String capabilities = context.intent() == null ? "AUTO"
            : context.intent().requiredCapabilities().stream().map(Enum::name).sorted().toList().toString();
        Map<String, Object> identityAttributes = new TreeMap<>(context.attributes());
        identityAttributes.remove(AnalysisContext.EXECUTION_MODE_ATTRIBUTE);
        String identity = String.join("|",
            text(context.kernelScope().tenantId()), text(context.kernelScope().userId()), text(request),
            text(context.skillId()), context.query(), context.documentIds().stream().sorted().toList().toString(),
            context.documentTags().stream().sorted().toList().toString(),
            context.roles().stream().sorted().toList().toString(), capabilities, identityAttributes.toString());
        if (request == null || request.isBlank()) identity += "|" + UUID.randomUUID();
        return "analysis-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private AnalysisExecutionOutcome withRuntimeMetadata(AnalysisExecutionOutcome outcome,
                                                         AnalysisExecutionMode mode,
                                                         String workflowId) {
        Map<String, Object> metadata = new LinkedHashMap<>(outcome.metadata());
        metadata.put("executionMode", mode.name());
        metadata.putIfAbsent("workflowFamily", outcome.workflowType().family().name());
        if (workflowId != null) metadata.put("runtimeWorkflowId", workflowId);
        return new AnalysisExecutionOutcome(outcome.schemaVersion(), outcome.workflowType(), outcome.plan(),
            outcome.verification(), outcome.evidenceBundle(), outcome.synthesis(), metadata);
    }

    /** Only Runtime decides whether metadata work continues or yields a user-facing outcome. */
    private AnalysisExecutionOutcome completeAssetGuidance(AnalysisContext context, AnalysisWorkflow workflow,
                                                          AnalysisExecutionOutcome primary) {
        AnalysisExecutionOutcome current = primary;
        boolean usable = current.verification() != null && current.verification().accepted()
            && !current.evidenceBundle().evidence().isEmpty();
        String state = String.valueOf(current.metadata().getOrDefault("guidanceState", "GUIDANCE_CONTEXT_REQUIRED"));
        String decision = !usable ? "INSUFFICIENT_EVIDENCE"
            : "GUIDANCE_READY".equals(state) ? "GUIDANCE_READY" : "NEEDS_SELECTION";
        Map<String, Object> metadata = new LinkedHashMap<>(current.metadata());
        metadata.put("runtimeGuidanceDecision", decision);
        metadata.put("runtimePublicStatus", usable ? "PARTIAL_SUCCESS" : "NO_PRESENTABLE_RESULT");
        metadata.put("guidanceExecutionPolicy", "PLAN_ONCE_ACQUIRE_ONCE_NO_REENTRY");
        return new AnalysisExecutionOutcome(current.schemaVersion(), current.workflowType(), current.plan(),
            current.verification(), current.evidenceBundle(), current.synthesis(), metadata);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
