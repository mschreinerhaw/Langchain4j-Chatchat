package com.chatchat.runtime.temporal.activity;

import com.chatchat.runtime.temporal.contract.TemporalWorkflowCommand;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface RuntimeOsWorkflowActivity {

    @ActivityMethod(name = "runtime-os-execute")
    TemporalWorkflowResult execute(TemporalWorkflowCommand command);
}
