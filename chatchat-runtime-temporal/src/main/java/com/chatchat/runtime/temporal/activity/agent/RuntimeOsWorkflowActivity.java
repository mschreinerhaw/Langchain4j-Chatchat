package com.chatchat.runtime.temporal.activity.agent;

import com.chatchat.runtime.temporal.contract.agent.TemporalAgentExecutionSlice;
import com.chatchat.runtime.temporal.contract.agent.TemporalAgentResumeCommand;
import com.chatchat.runtime.temporal.contract.core.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.core.TemporalWorkflowResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface RuntimeOsWorkflowActivity {

    @ActivityMethod(name = "runtime-os-execute")
    TemporalWorkflowResult execute(TemporalWorkflowCommand command);

    @ActivityMethod(name = "runtime-os-agent-bootstrap-v1")
    TemporalAgentExecutionSlice bootstrapAgent(TemporalWorkflowCommand command);

    @ActivityMethod(name = "runtime-os-agent-resume-v1")
    TemporalAgentExecutionSlice resumeAgent(TemporalAgentResumeCommand command);
}
