package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Independent Activity boundary for one governed Runtime OS tool call. */
@ActivityInterface
public interface RuntimeOsToolActivity {

    @ActivityMethod(name = "runtime-os-tool-execute-v1")
    ToolRuntimeExecution execute(TemporalToolActivityCommand command);
}
