package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.workflow.*;
import org.springframework.stereotype.Service;

import java.util.List;

/** Agent Runtime entry point for all problem-analysis workflows. */
@Service
public class DefaultAnalysisWorkflowRuntime implements AnalysisRuntimePort {
    private final AnalysisWorkflowRouter router;

    public DefaultAnalysisWorkflowRuntime(List<AnalysisWorkflow> workflows) {
        this.router = new AnalysisWorkflowRouter(new StandardAnalysisQueryAnalyzer(), workflows);
    }

    @Override
    public AnalysisResult analyze(AnalysisContext context) {
        AnalysisWorkflowRouter.RoutedWorkflow routed = router.route(context);
        return routed.workflow().execute(routed.context(), routed.context().kernelScope());
    }
}
