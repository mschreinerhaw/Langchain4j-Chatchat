package com.chatchat.runtime.temporal.workflow;

import com.chatchat.agents.runtime.plan.execution.AgentRunExecutionSlice;
import com.chatchat.runtime.temporal.activity.RuntimeOsWorkflowActivity;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import com.chatchat.runtime.temporal.contract.TemporalAgentExecutionSlice;
import com.chatchat.runtime.temporal.contract.TemporalAgentResumeCommand;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionCommand;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionResult;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.ChildWorkflowOptions;

import java.time.Duration;

/**
 * First phase-three child workflow. It preserves the mature Agent executor as one Activity while
 * providing the durable boundary where plan branches and tool Activities will be introduced.
 */
public class RuntimeOsAgentExecutionWorkflowImpl implements RuntimeOsAgentExecutionWorkflow {

    public static final String PLAN_SUSPEND_RESUME_CHANGE_ID =
        "runtime-os-agent-plan-suspend-resume-v1";

    @Override
    public TemporalWorkflowResult execute(TemporalWorkflowCommand command) {
        ActivityOptions options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(command.activityStartToCloseSeconds()))
            .setHeartbeatTimeout(Duration.ofSeconds(command.activityHeartbeatSeconds()))
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(command.activityMaximumAttempts())
                .build())
            .build();
        RuntimeOsWorkflowActivity activity =
            Workflow.newActivityStub(RuntimeOsWorkflowActivity.class, options);
        if (!"agent-run-v1".equals(command.workflowType())) {
            return activity.execute(command);
        }
        int version = Workflow.getVersion(
            PLAN_SUSPEND_RESUME_CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        if (version == Workflow.DEFAULT_VERSION) {
            return activity.execute(command);
        }
        TemporalAgentExecutionSlice slice = activity.bootstrapAgent(command);
        int suspensionCount = 0;
        while (AgentRunExecutionSlice.PLAN_SUSPENDED.equals(slice.status())) {
            if (++suspensionCount > 16) {
                throw ApplicationFailure.newNonRetryableFailure(
                    "Agent plan suspension limit exceeded",
                    "AGENT_EXECUTION_SUSPENSION_LIMIT_EXCEEDED");
            }
            var suspended = slice.suspendedPlan();
            ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder()
                .setWorkflowId(Workflow.getInfo().getWorkflowId()
                    + "::plan-execution::" + suspended.planAttempt())
                .setTaskQueue(Workflow.getInfo().getTaskQueue())
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build();
            RuntimeOsPlanExecutionWorkflow child = Workflow.newChildWorkflowStub(
                RuntimeOsPlanExecutionWorkflow.class, childOptions);
            TemporalPlanExecutionResult planResult = child.execute(
                new TemporalPlanExecutionCommand(
                    TemporalPlanExecutionCommand.SCHEMA_VERSION,
                    suspended.execution(),
                    command.activityStartToCloseSeconds(),
                    command.activityHeartbeatSeconds(),
                    false));
            slice = activity.resumeAgent(new TemporalAgentResumeCommand(suspended, planResult));
        }
        if (!AgentRunExecutionSlice.COMPLETED.equals(slice.status())
            || slice.outputJson() == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                "Agent suspend/resume loop returned an invalid terminal slice",
                "AGENT_EXECUTION_INVALID_TERMINAL_SLICE");
        }
        return new TemporalWorkflowResult(slice.outputJson());
    }
}
