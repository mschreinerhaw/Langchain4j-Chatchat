package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.runtime.analysis.plan.PlanStep;
import com.chatchat.common.runtime.analysis.plan.StandardWorkflowPlan;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.workflow.AbstractAnalysisWorkflow;


import java.util.List;
import java.util.Map;
import java.util.UUID;

abstract class OperatorBackedAnalysisWorkflow extends AbstractAnalysisWorkflow {
    private final AnalysisOperatorRegistry operators;
    private final AnalysisWorkflowType workflowType;
    private final AnalysisCapability capability;
    private final List<String> operations;

    protected OperatorBackedAnalysisWorkflow(AnalysisOperatorRegistry operators,
                                             AnalysisWorkflowType workflowType,
                                             AnalysisCapability capability,
                                             List<String> operations) {
        this.operators = operators;
        this.workflowType = workflowType;
        this.capability = capability;
        this.operations = List.copyOf(operations);
    }

    @Override public AnalysisWorkflowType type() { return workflowType; }
    @Override public String workflowId() { return "problem-analysis." + workflowType.name().toLowerCase(); }
    @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) {
        return intent.requiredCapabilities().size() == 1 && intent.requiredCapabilities().contains(capability);
    }

    @Override
    protected AnalysisScope resolveScope(AnalysisContext context) {
        return new AnalysisScope(context.kernelScope().tenantId(), context.kernelScope().userId(),
            context.roles(), context.documentIds(), Map.of("skillId", context.skillId()));
    }

    @Override
    protected WorkflowPlan plan(AnalysisContext context, AnalysisScope scope) {
        List<PlanStep> steps = java.util.stream.IntStream.range(0, operations.size())
            .mapToObj(index -> new PlanStep(String.valueOf(index + 1), operations.get(index), capability,
                true, Map.of())).toList();
        return new StandardWorkflowPlan(UUID.randomUUID().toString(), workflowType, steps,
            List.of(new EvidenceRequirement(capability.name(), true, 1, "provider response must verify")));
    }

    @Override
    protected WorkflowExecutionResult executePlan(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        return operators.resolve(capability, context)
            .map(operator -> operator.execute(context, scope, plan))
            .orElseGet(() -> new WorkflowExecutionResult(List.of(), Map.of(),
                List.of("No available operator for " + capability)));
    }

    @Override
    protected VerificationResult verify(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan,
                                        WorkflowExecutionResult execution) {
        boolean accepted = !execution.evidence().isEmpty();
        return new VerificationResult(accepted, accepted ? execution.evidence() : List.of(),
            accepted ? List.of() : execution.observations());
    }
}
