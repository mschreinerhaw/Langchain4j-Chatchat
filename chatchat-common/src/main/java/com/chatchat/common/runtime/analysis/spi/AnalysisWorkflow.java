package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;

import com.chatchat.common.runtime.workflow.RuntimeWorkflow;

public interface AnalysisWorkflow extends RuntimeWorkflow<AnalysisContext, AnalysisExecutionOutcome> {
    AnalysisWorkflowType type();
    boolean supports(AnalysisContext context, AnalysisIntent intent);
    default int priority() { return 0; }
}
