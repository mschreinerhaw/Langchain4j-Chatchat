package com.chatchat.common.runtime.capability;

import com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily;
import java.util.List;

/** Only validated analysis tasks select workflows; raw question text is not a routing input. */
public final class CapabilityWorkflowRouter {
    public List<RuntimeWorkflowFamily> requiredWorkflows(ProblemAnalysisPlan plan) {
        if (plan == null || plan.status() != ProblemAnalysisPlan.Status.READY)
            throw new IllegalArgumentException("A ready problem analysis plan is required before workflow selection");
        return plan.tasks().stream().map(task -> switch (task.intent()) {
            case DIRECT_ANSWER -> RuntimeWorkflowFamily.DIRECT_ANSWER;
            case DOCUMENT_UNDERSTANDING -> RuntimeWorkflowFamily.DOCUMENT;
            case DATA_ANALYSIS -> RuntimeWorkflowFamily.DATA_ANALYSIS;
            case ASSET_USAGE_GUIDANCE -> RuntimeWorkflowFamily.ASSET_GUIDANCE;
            case ACTION_EXECUTION -> RuntimeWorkflowFamily.ACTION;
        }).distinct().toList();
    }
    public RuntimeWorkflowFamily route(ProblemAnalysisPlan plan) {
        var workflows = requiredWorkflows(plan);
        if (workflows.size() != 1) throw new IllegalArgumentException("Multiple workflow objectives require clarification before composite execution");
        return workflows.get(0);
    }
}
