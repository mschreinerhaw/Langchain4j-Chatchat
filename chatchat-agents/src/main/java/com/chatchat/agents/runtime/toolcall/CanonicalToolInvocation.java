package com.chatchat.agents.runtime.toolcall;

import java.util.Map;
import java.util.UUID;

/** Strict local protocol object used after model arguments have passed schema compilation. */
public record CanonicalToolInvocation(
    String schemaVersion,
    String requestId,
    String stepId,
    String toolName,
    CompiledToolArguments arguments,
    Map<String, Object> context
) {
    public static final String SCHEMA_VERSION = "canonical_tool_invocation.v1";

    public CanonicalToolInvocation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
            ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported canonical invocation schema: " + schemaVersion);
        }
        requestId = requestId == null || requestId.isBlank()
            ? UUID.randomUUID().toString() : requestId.trim();
        stepId = stepId == null || stepId.isBlank() ? null : stepId.trim();
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        toolName = toolName.trim();
        if (arguments == null || !arguments.valid()) {
            throw new IllegalArgumentException("Canonical invocation requires valid compiled arguments");
        }
        context = CompiledToolArguments.immutableMap(context);
    }

    public CanonicalToolInvocation withContext(Map<String, Object> governedContext) {
        return new CanonicalToolInvocation(schemaVersion, requestId, stepId, toolName, arguments, governedContext);
    }
}
