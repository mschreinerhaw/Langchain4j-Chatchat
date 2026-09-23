package com.chatchat.common.runtime.analysis.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.workflow.AbstractRuntimeWorkflow;

import java.util.Map;

/** Template method for UNDERSTAND -> SCOPE -> PLAN -> EXECUTE -> VERIFY -> SYNTHESIZE -> RETURN. */
public abstract class AbstractAnalysisWorkflow extends AbstractRuntimeWorkflow<AnalysisContext, AnalysisResult>
    implements AnalysisWorkflow {

    @Override
    protected final AnalysisResult doExecute(AnalysisContext input, KernelDataScope kernelScope) {
        AnalysisContext understood = understand(input);
        AnalysisScope scope = resolveScope(understood);
        WorkflowPlan plan = plan(understood, scope);
        WorkflowExecutionResult execution = executePlan(understood, scope, plan);
        VerificationResult verification = verify(understood, scope, plan, execution);
        EvidenceBundle bundle = evidenceBundle(understood, plan, execution, verification);
        return synthesize(understood, scope, plan, execution, verification, bundle);
    }

    @Override
    protected final AnalysisResult doExecute(AnalysisContext input) {
        throw new UnsupportedOperationException("Kernel-scoped execution is required");
    }

    protected AnalysisContext understand(AnalysisContext context) { return context; }
    protected abstract AnalysisScope resolveScope(AnalysisContext context);
    protected abstract WorkflowPlan plan(AnalysisContext context, AnalysisScope scope);
    protected abstract WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope,
                                                           WorkflowPlan plan);
    protected abstract VerificationResult verify(AnalysisContext context, AnalysisScope scope,
                                                 WorkflowPlan plan, WorkflowExecutionResult execution);

    protected EvidenceBundle evidenceBundle(AnalysisContext context, WorkflowPlan plan,
                                            WorkflowExecutionResult execution,
                                            VerificationResult verification) {
        return new EvidenceBundle(EvidenceBundle.SCHEMA_VERSION, verification.acceptedEvidence(),
            verification.findings(), Map.of("workflowType", type().name(), "planId", plan.planId()));
    }

    protected AnalysisResult synthesize(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                        WorkflowExecutionResult execution, VerificationResult verification,
                                        EvidenceBundle bundle) {
        return new AnalysisResult(AnalysisResult.SCHEMA_VERSION, type(), plan, verification, bundle,
            "", Map.of("workflowId", workflowId()));
    }
}
