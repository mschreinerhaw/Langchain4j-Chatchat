package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.workflow.AnalysisCapability;
import com.chatchat.common.runtime.analysis.workflow.AnalysisWorkflowType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StructuredDataAnalysisWorkflow extends OperatorBackedAnalysisWorkflow {
    public StructuredDataAnalysisWorkflow(AnalysisOperatorRegistry operators) {
        super(operators, AnalysisWorkflowType.STRUCTURED_DATA, AnalysisCapability.STRUCTURED_DATA,
            List.of("METRIC_ENTITY_RESOLUTION", "DATASET_ROUTE", "SQL_PLAN", "QUERY_EXECUTE",
                "DATA_QUALITY_VERIFY", "AGGREGATE"));
    }
}
