package com.chatchat.common.runtime.analysis.workflow;

/** Atomic capability port used by non-document and composite workflows. */
public interface AnalysisCapabilityOperator {
    AnalysisCapability capability();
    boolean available(AnalysisContext context);
    WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan);
}
