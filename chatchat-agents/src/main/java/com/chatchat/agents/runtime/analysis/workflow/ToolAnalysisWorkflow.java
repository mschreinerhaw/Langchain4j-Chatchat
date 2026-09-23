package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.workflow.AnalysisCapability;
import com.chatchat.common.runtime.analysis.workflow.AnalysisWorkflowType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ToolAnalysisWorkflow extends OperatorBackedAnalysisWorkflow {
    public ToolAnalysisWorkflow(AnalysisOperatorRegistry operators) {
        super(operators, AnalysisWorkflowType.TOOL, AnalysisCapability.TOOL_CALL,
            List.of("CAPABILITY_ROUTE", "PERMISSION_CHECK", "TOOL_SELECT", "PARAMETER_BIND",
                "TOOL_EXECUTE", "RESPONSE_VERIFY"));
    }
}
