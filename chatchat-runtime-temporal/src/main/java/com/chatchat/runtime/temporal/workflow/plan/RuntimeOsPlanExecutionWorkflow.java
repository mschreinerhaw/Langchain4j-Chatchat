package com.chatchat.runtime.temporal.workflow.plan;

import com.chatchat.runtime.temporal.contract.plan.TemporalPlanExecutionCommand;
import com.chatchat.runtime.temporal.contract.plan.TemporalPlanExecutionResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RuntimeOsPlanExecutionWorkflow {

    @WorkflowMethod(name = "runtime-os-plan-execution-v1")
    TemporalPlanExecutionResult execute(TemporalPlanExecutionCommand command);
}
