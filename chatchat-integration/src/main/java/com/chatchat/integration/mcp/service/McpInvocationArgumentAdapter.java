package com.chatchat.integration.mcp.service;

import com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler;

import java.util.Map;

/**
 * Single MCP invocation boundary for adapting Agent semantic arguments to a
 * remote tool's published business contract.
 *
 * <p>The Agent runtime may attach orchestration envelopes used for discovery,
 * binding and execution planning. Those envelopes are deliberately not part of
 * a remote tool's strict JSON Schema. This adapter promotes schema-compatible
 * values from such envelopes, applies declared aliases/defaults/types, and
 * returns only the remote business arguments. Transport identity and tracing
 * context are attached separately by {@link McpToolRegistryBridge}.</p>
 */
final class McpInvocationArgumentAdapter {

    private final ToolArgumentCompiler compiler = new ToolArgumentCompiler();

    ToolArgumentCompiler.CompilationResult adapt(Map<String, Object> semanticArguments,
                                                  Map<String, Object> publishedInputSchema) {
        return compiler.compile(semanticArguments, publishedInputSchema);
    }
}
