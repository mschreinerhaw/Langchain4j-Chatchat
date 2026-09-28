package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisExecutionMode;
import com.chatchat.common.runtime.analysis.routing.AnalysisWorkflowRouter;
import com.chatchat.common.runtime.analysis.routing.StandardAnalysisQueryAnalyzer;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.common.runtime.analysis.spi.AnalysisEvidenceArchivePort;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;
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
    private final AtomicBoolean registered = new AtomicBoolean(false);

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
        if (outcome.verification() == null || !outcome.verification().accepted()
            || outcome.evidenceBundle().evidence().isEmpty()) return outcome;
        if (context.kernelScope().tenantId() == null || context.kernelScope().userId() == null)
            return outcome;
        try {
            AnalysisEvidenceArchivePort store = evidenceArchive.get();
            if (store == null) throw new IllegalStateException("Evidence archive unavailable");
            AnalysisEvidenceArchivePort.Reference reference = store.archive(context, outcome.evidenceBundle());
            Map<String, Object> metadata = new LinkedHashMap<>(outcome.metadata());
            metadata.put("evidenceArchiveId", reference.archiveId());
            metadata.put("evidenceSha256", reference.sha256());
            metadata.put("evidenceByteLength", reference.byteLength());
            return new AnalysisExecutionOutcome(outcome.schemaVersion(), outcome.workflowType(), outcome.plan(),
                outcome.verification(), outcome.evidenceBundle(), outcome.synthesis(), metadata);
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
        AnalysisExecutionOutcome primary = routed.workflow().execute(
            routed.context(), routed.context().kernelScope());
        return recoverAndContinue(routed.context(), routed.workflow(), primary);
    }

    private AnalysisExecutionOutcome recoverAndContinue(AnalysisContext context, AnalysisWorkflow workflow,
                                                         AnalysisExecutionOutcome primary) {
        EvidenceBundle current = primary.evidenceBundle();
        EvidenceStateInspector.State state = evidenceInspector.inspect(current, primary.metadata());
        Map<String, Object> metadata = new LinkedHashMap<>(primary.metadata());
        Map<String, Object> acquisitionMetadata = new LinkedHashMap<>(primary.metadata());
        metadata.put("runtimePrimaryPath", (primary.workflowType() == com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType.DOCUMENT
            ? AnalysisRuntimePath.DOCUMENT_RETRIEVAL : AnalysisRuntimePath.PRIMARY_ANALYSIS).name());
        List<Map<String, Object>> trace = new java.util.ArrayList<>();
        String status = "NOT_NEEDED";
        int maxRounds = recoveryMaxRounds(context);
        if (!state.issues().isEmpty()) status = maxRounds == 0 ? "DISABLED" : "BUDGET_EXHAUSTED";
        for (int round = 1; round <= maxRounds && !state.issues().isEmpty(); round++) {
            EvidenceGap issue = null;
            EvidenceRecoveryWorkflow recovery = null;
            try {
                for (EvidenceGap candidate : state.issues()) {
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
            current = mergeEvidence(current, recovered.evidence());
            trace.add(Map.of("round", round, "gap", issue.reason().name(),
                "status", recovered.status().name(), "workflow", recovery.getClass().getSimpleName(),
                "evidenceCount", current.evidence().size()));
            acquisitionMetadata.putAll(recovered.metadata());
            state = evidenceInspector.inspect(current, acquisitionMetadata);
            status = recovered.status().name();
            if (recovered.status() == RecoveryStatus.COMPLETE || recovered.status() == RecoveryStatus.EXHAUSTED
                || recovered.status() == RecoveryStatus.FAILED) break;
            if (current.evidence().equals(previous.evidence())) {
                status = "NO_NEW_EVIDENCE";
                break;
            }
            if (state.issues().isEmpty()) {
                status = "COMPLETE";
                break;
            }
            status = "BUDGET_EXHAUSTED";
        }
        metadata.put("evidenceState", state.projection());
        metadata.put("evidenceRecoveryStatus", status);
        metadata.put("evidenceRecoveryTrace", List.copyOf(trace));
        metadata.put("runtimeRoute", "CONTINUE_ANALYSIS");
        if (state.issues().isEmpty() && trace.isEmpty()) {
            return outcome(primary, primary.verification(), current, metadata);
        }
        // The analysis workflow owns verification and synthesis, including when recovery failed.
        AnalysisExecutionOutcome continued = workflow.continueAfterRecovery(context, primary, current, Map.copyOf(metadata));
        Map<String, Object> combined = new LinkedHashMap<>(continued.metadata());
        combined.putAll(metadata);
        return outcome(continued, continued.verification(), continued.evidenceBundle(), combined);
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
        int resolved = value instanceof Number number ? number.intValue() : 5;
        return Math.max(0, Math.min(6, resolved));
    }

    private AnalysisExecutionOutcome outcome(AnalysisExecutionOutcome source,
                                             VerificationResult verification,
                                             EvidenceBundle evidence,
                                             Map<String, Object> metadata) {
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
        if (workflowId != null) metadata.put("runtimeWorkflowId", workflowId);
        return new AnalysisExecutionOutcome(outcome.schemaVersion(), outcome.workflowType(), outcome.plan(),
            outcome.verification(), outcome.evidenceBundle(), outcome.synthesis(), metadata);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
