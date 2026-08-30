package com.chatchat.runtime.temporal.activity;

import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import com.chatchat.runtime.temporal.contract.TemporalAgentExecutionSlice;
import com.chatchat.runtime.temporal.contract.TemporalAgentResumeCommand;
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
