package com.chatchat.common.runtime.analysis.workflow;

import java.util.List;

public interface WorkflowPlan {
    String planId();
    AnalysisWorkflowType workflowType();
    List<PlanStep> steps();
    List<EvidenceRequirement> evidenceRequirements();
}
