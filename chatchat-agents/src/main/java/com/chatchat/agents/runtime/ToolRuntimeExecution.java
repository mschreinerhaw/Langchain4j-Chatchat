package com.chatchat.agents.runtime;

import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;

import java.util.Map;

public record ToolRuntimeExecution(
    ToolOutput output,
    ToolMetadata metadata,
    InteractionToolTrace trace,
    String outcome,
    Map<String, Object> audit
) {
}
