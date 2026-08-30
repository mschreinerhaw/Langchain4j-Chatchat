package com.chatchat.runtime.temporal.workflow;

import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionCommand;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RuntimeOsPlanExecutionWorkflow {

    @WorkflowMethod(name = "runtime-os-plan-execution-v1")
    TemporalPlanExecutionResult execute(TemporalPlanExecutionCommand command);
}
