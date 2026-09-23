package com.chatchat.common.runtime.analysis.plan;

import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;

import java.util.List;

public interface WorkflowPlan {
    String planId();
    AnalysisWorkflowType workflowType();
    List<PlanStep> steps();
    List<EvidenceRequirement> evidenceRequirements();
}
