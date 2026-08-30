package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.AgentRunExecutor;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.kernel.KernelDataScope;

/**
 * Agent executor that may yield at an InterpretationPlan boundary and resume after the durable
 * execution Workflow returns. Implementations must not invoke the planner again while resuming.
 */
public interface ResumableAgentRunExecutor extends AgentRunExecutor {

    AgentRunExecutionSlice executeUntilPlanSuspension(
        AgentRunRequest request, KernelDataScope scope);

    AgentRunExecutionSlice resumeAfterPlanExecution(
        AgentPlanPipelineContinuation continuation,
        InterpretationPlanRuntime.ExecutionResult executionResult,
        KernelDataScope scope);
}
