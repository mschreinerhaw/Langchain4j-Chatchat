package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisExecutionMode;
import com.chatchat.common.runtime.analysis.routing.AnalysisWorkflowRouter;
import com.chatchat.common.runtime.analysis.routing.StandardAnalysisQueryAnalyzer;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;

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
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows) {
        this(workflows, () -> null);
    }

    public DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows, WorkflowRuntime workflowRuntime) {
        this(workflows, () -> workflowRuntime);
    }

    @Autowired
    public DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows,
                                          ObjectProvider<WorkflowRuntime> workflowRuntime) {
        this(workflows, workflowRuntime::getIfAvailable);
    }

    private DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows,
                                           Supplier<WorkflowRuntime> workflowRuntime) {
        this.router = new AnalysisWorkflowRouter(new StandardAnalysisQueryAnalyzer(), workflows);
        this.workflowRuntime = workflowRuntime;
    }

    @Override
    public AnalysisExecutionOutcome analyze(AnalysisContext context) {
        if (context.executionMode() == AnalysisExecutionMode.DURABLE) {
            return executeDurably(context);
        }
        return withRuntimeMetadata(executeInline(context), AnalysisExecutionMode.INLINE, null);
    }

    private AnalysisExecutionOutcome executeInline(AnalysisContext context) {
        AnalysisWorkflowRouter.RoutedWorkflow routed = router.route(context);
        return routed.workflow().execute(routed.context(), routed.context().kernelScope());
    }

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
        AnalysisExecutionOutcome outcome = executeInline(context);
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
