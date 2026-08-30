package com.chatchat.runtime.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface RuntimeOsWorkflowActivity {

    @ActivityMethod(name = "runtime-os-execute")
    TemporalWorkflowResult execute(TemporalWorkflowCommand command);
}
