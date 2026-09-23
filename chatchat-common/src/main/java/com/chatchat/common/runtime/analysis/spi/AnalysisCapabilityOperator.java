package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;

/** Atomic capability port used by non-document and composite workflows. */
public interface AnalysisCapabilityOperator {
    AnalysisCapability capability();
    boolean available(AnalysisContext context);
    WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan);
}
