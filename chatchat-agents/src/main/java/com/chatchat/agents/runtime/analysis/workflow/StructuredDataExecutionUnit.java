package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.capability.ComputeNodeType;
import com.chatchat.common.runtime.capability.ExecutionUnit;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Data Compute entry backed by the governed SQL/data-quality analytical workflow. */
@Component
public class StructuredDataExecutionUnit implements ExecutionUnit<AnalysisContext, AnalysisExecutionOutcome> {
    private final StructuredDataAnalysisWorkflow workflow;

    public StructuredDataExecutionUnit(StructuredDataAnalysisWorkflow workflow) { this.workflow = workflow; }

    @Override public ComputeNodeType nodeType() { return ComputeNodeType.DATA; }
    @Override public Class<AnalysisContext> inputType() { return AnalysisContext.class; }
    @Override public Class<AnalysisExecutionOutcome> outputType() { return AnalysisExecutionOutcome.class; }

    @Override public AnalysisExecutionOutcome execute(AnalysisContext input, KernelDataScope scope) {
        if (input == null || scope == null || !scope.equals(input.kernelScope()))
            throw new IllegalArgumentException("Data execution scope mismatch");
        if (input.intent() == null || !input.intent().requiredCapabilities().equals(Set.of(AnalysisCapability.STRUCTURED_DATA)))
            throw new IllegalArgumentException("Data node requires a structured-data intent");
        return workflow.execute(input, scope);
    }
}
