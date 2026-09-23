package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.workflow.AnalysisCapability;
import com.chatchat.common.runtime.analysis.workflow.AnalysisWorkflowType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ComputationAnalysisWorkflow extends OperatorBackedAnalysisWorkflow {
    public ComputationAnalysisWorkflow(AnalysisOperatorRegistry operators) {
        super(operators, AnalysisWorkflowType.COMPUTATION, AnalysisCapability.COMPUTATION,
            List.of("INPUT_RESOLUTION", "REQUIRED_DATA_CHECK", "ALGORITHM_SELECT", "COMPUTE",
                "BOUNDARY_VALIDATE", "RESULT_VERIFY"));
    }
}
