package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExternalResearchWorkflow extends OperatorBackedAnalysisWorkflow {
    public ExternalResearchWorkflow(AnalysisOperatorRegistry operators) {
        super(operators, AnalysisWorkflowType.EXTERNAL_RESEARCH, AnalysisCapability.EXTERNAL_RESEARCH,
            List.of("FRESHNESS_REQUIREMENT", "TRUSTED_SOURCE_SELECT", "WEB_SEARCH", "SOURCE_RETRIEVE",
                "DEDUPLICATE", "AUTHORITY_FRESHNESS_RANK", "CROSS_SOURCE_VERIFY"));
    }
}
