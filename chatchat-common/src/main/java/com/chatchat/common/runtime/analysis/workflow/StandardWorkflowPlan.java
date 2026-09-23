package com.chatchat.common.runtime.analysis.workflow;

import java.util.List;

public record StandardWorkflowPlan(String planId, AnalysisWorkflowType workflowType,
                                   List<PlanStep> steps,
                                   List<EvidenceRequirement> evidenceRequirements) implements WorkflowPlan {
    public StandardWorkflowPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        evidenceRequirements = evidenceRequirements == null ? List.of() : List.copyOf(evidenceRequirements);
    }
}
