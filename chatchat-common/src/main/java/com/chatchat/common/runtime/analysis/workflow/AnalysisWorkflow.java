package com.chatchat.common.runtime.analysis.workflow;

import com.chatchat.common.runtime.workflow.RuntimeWorkflow;

public interface AnalysisWorkflow extends RuntimeWorkflow<AnalysisContext, AnalysisResult> {
    AnalysisWorkflowType type();
    boolean supports(AnalysisContext context, AnalysisIntent intent);
    default int priority() { return 0; }
}
