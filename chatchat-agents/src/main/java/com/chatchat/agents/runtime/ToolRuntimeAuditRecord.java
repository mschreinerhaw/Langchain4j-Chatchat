package com.chatchat.agents.runtime;

import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;

import java.util.Map;

public record ToolRuntimeAuditRecord(
    ToolRuntimeRequest request,
    ToolMetadata metadata,
    ToolOutput output,
    InteractionToolTrace trace,
    String outcome,
    String errorCode,
    long durationMs,
    Map<String, Object> runtimeMetadata
) {
}
