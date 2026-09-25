package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Binds the established analytical workflow runtime to the five-node control plane. */
@Component
public class AnalysisWorkflowExecutionUnit implements ExecutionUnit<AnalysisContext, AnalysisExecutionOutcome> {
    private final ObjectProvider<AnalysisRuntimePort> runtime;

    public AnalysisWorkflowExecutionUnit(ObjectProvider<AnalysisRuntimePort> runtime) { this.runtime = runtime; }

    @Override public ComputeNodeType nodeType() { return ComputeNodeType.WORKFLOW; }
    @Override public Class<AnalysisContext> inputType() { return AnalysisContext.class; }
    @Override public Class<AnalysisExecutionOutcome> outputType() { return AnalysisExecutionOutcome.class; }

    @Override public AnalysisExecutionOutcome execute(AnalysisContext input, KernelDataScope scope) {
        if (input == null || scope == null || !scope.equals(input.kernelScope()))
            throw new IllegalArgumentException("Workflow execution scope mismatch");
        return runtime.getObject().analyze(input);
    }
}
